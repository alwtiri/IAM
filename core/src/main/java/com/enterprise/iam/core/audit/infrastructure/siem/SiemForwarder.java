package com.enterprise.iam.core.audit.infrastructure.siem;

import com.enterprise.iam.kernel.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Forwards the tamper-evident audit trail to a SIEM over HTTPS (Phase 8): batches of events in order, at-least-once,
 * each POST signed with HMAC-SHA256 ({@code X-IAM-Signature: sha256=<hex>}) so the receiver can verify origin and
 * integrity. The cursor (occurred_at, id) is only advanced after a 2xx answer; the event hash is included so a SIEM can
 * cross-check against the platform's chain. Details contain no secrets (enforced when events are recorded).
 */
public class SiemForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(SiemForwarder.class);
    static final String SINK = "siem-webhook";
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final URI url;
    private final byte[] secret;
    private final HttpClient http;

    public SiemForwarder(JdbcClient jdbc, URI url, String secret) {
        this.jdbc = jdbc;
        this.url = url;
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        if (url != null && !"https".equals(url.getScheme()) && !"localhost".equals(url.getHost()) && !url.getHost().startsWith("127.")) {
            LOG.warn("SIEM webhook {} is not HTTPS; audit events would travel unencrypted", url.getHost());
        }
    }

    @Scheduled(fixedDelayString = "${iam.siem.interval:PT30S}", initialDelayString = "PT1M")
    void forward() {
        if (url == null) {
            return;
        }
        try {
            for (int i = 0; i < 10 && forwardBatch() == BATCH; i++) {
                // keep draining while full batches arrive
            }
        } catch (RuntimeException e) {
            LOG.warn("SIEM forwarding failed: {}", e.getMessage());
            record(null, null, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    int forwardBatch() {
        jdbc.sql("INSERT INTO audit.forwarding_state (sink) VALUES (:s) ON CONFLICT DO NOTHING").param("s", SINK).update();
        Map<String, Object> state = jdbc.sql("SELECT last_occurred_at, last_id FROM audit.forwarding_state WHERE sink = :s").param("s", SINK)
                .query().singleRow();
        Timestamp lastAt = (Timestamp) state.get("last_occurred_at");
        UUID lastId = (UUID) state.get("last_id");
        String sql = """
                SELECT id, occurred_at, actor_identity_id, actor_type, action, object_type, object_id, target_id, result, reason,
                       correlation_id, host(ip) AS ip, provider_instance_id, details::text AS details, encode(hash, 'hex') AS hash
                FROM audit.audit_event""";
        List<Map<String, Object>> rows = lastAt == null
                ? jdbc.sql(sql + " ORDER BY occurred_at, id LIMIT :n").param("n", BATCH).query().listOfRows()
                : jdbc.sql(sql + " WHERE (occurred_at, id) > (:at, :id) ORDER BY occurred_at, id LIMIT :n")
                        .param("at", lastAt).param("id", lastId).param("n", BATCH).query().listOfRows();
        if (rows.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", String.valueOf(r.get("id")));
            e.put("occurredAt", ((Timestamp) r.get("occurred_at")).toInstant().toString());
            for (String k : List.of("actor_identity_id", "actor_type", "action", "object_type", "object_id", "target_id", "result", "reason",
                    "correlation_id", "ip", "provider_instance_id", "hash")) {
                if (r.get(k) != null) {
                    e.put(camel(k), String.valueOf(r.get(k)));
                }
            }
            e.put("details", Json.parse(String.valueOf(r.get("details"))));
            events.add(e);
        }
        String body = Json.write(Map.of("source", "enterprise-iam", "schema", "iam.audit.v1", "events", events));
        HttpRequest.Builder req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (secret.length > 0) {
            req.header("X-IAM-Signature", "sha256=" + hmac(body));
        }
        HttpResponse<Void> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            record(null, null, 0, "unreachable: " + e.getClass().getSimpleName());
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (resp.statusCode() / 100 != 2) {
            record(null, null, 0, "HTTP " + resp.statusCode());
            return 0;
        }
        Map<String, Object> last = rows.get(rows.size() - 1);
        record(((Timestamp) last.get("occurred_at")).toInstant(), (UUID) last.get("id"), rows.size(), null);
        return rows.size();
    }

    private void record(Instant at, UUID id, int count, String error) {
        if (at == null) {
            jdbc.sql("UPDATE audit.forwarding_state SET last_error = :e, updated_at = now() WHERE sink = :s").param("e", error).param("s", SINK).update();
        } else {
            jdbc.sql("""
                    UPDATE audit.forwarding_state SET last_occurred_at = :at, last_id = :id, forwarded_total = forwarded_total + :n,
                        last_success_at = now(), last_error = NULL, updated_at = now() WHERE sink = :s""")
                    .param("at", Timestamp.from(at)).param("id", id).param("n", count).param("s", SINK).update();
        }
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String camel(String snake) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }
}
