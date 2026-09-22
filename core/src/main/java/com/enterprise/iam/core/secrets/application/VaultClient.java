package com.enterprise.iam.core.secrets.application;

import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Json;
import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Minimal Vault HTTP client (AppRole auth, KV v2, health) on {@code java.net.http} — no framework dependency, strict
 * timeouts, fail closed (ADR-0005, G3). The Vault token is kept in memory only and renewed by re-login before expiry.
 */
public class VaultClient {

    private static final Logger LOG = Logger.getLogger(VaultClient.class.getName());
    private static final Pattern PATH = Pattern.compile("^[a-z0-9][a-z0-9/_-]{0,200}$");

    /** AppRole credentials, read lazily (e.g. from mounted secret files) so the Core starts without Vault. */
    public record AppRoleCredentials(String roleId, Secret secretId) {
    }

    public enum HealthState { ACTIVE, STANDBY, SEALED, UNINITIALIZED, UNREACHABLE }

    private final URI address;
    private final String kvMount;
    private final Supplier<AppRoleCredentials> credentials;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Clock clock;

    private volatile Secret token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public VaultClient(URI address, String kvMount, Supplier<AppRoleCredentials> credentials, HttpClient http,
                       Duration requestTimeout, Clock clock) {
        this.address = Objects.requireNonNull(address, "address");
        this.kvMount = Objects.requireNonNull(kvMount, "kvMount");
        this.credentials = credentials;
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    public static HttpClient defaultHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String kvMount() {
        return kvMount;
    }

    /** Writes a KV v2 secret with a single key {@code value}; returns the new version number. */
    public long writeKv(String path, Secret value) {
        requirePath(path);
        char[] chars = value.reveal(); // semgrep-justified: serialized directly into the TLS request body to Vault
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", Map.of("value", new String(chars)));
            HttpResponse<String> r = send("POST", "/v1/" + kvMount + "/data/" + path, Json.write(body), true);
            expect2xx(r, "write");
            Object version = Json.path(Json.parseObject(r.body()), "data", "version");
            if (!(version instanceof BigDecimal v)) {
                throw IamException.secretsUnavailable(null);
            }
            return v.longValueExact();
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Reads one version of a KV v2 secret written by {@link #writeKv}. Used only for credential-handle redemption; the
     * value is handed to the worker and never logged or cached.
     */
    public Secret readKv(String path, long version) {
        requirePath(path);
        HttpResponse<String> r = send("GET", "/v1/" + kvMount + "/data/" + path + "?version=" + version, null, true);
        expect2xx(r, "read");
        Object value = Json.path(Json.parseObject(r.body()), "data", "data", "value");
        if (!(value instanceof String v)) {
            throw IamException.secretsUnavailable(null);
        }
        return Secret.of(v);
    }

    /** Destroys all versions and metadata of a KV v2 path. Missing paths are treated as already destroyed. */
    public void destroyKv(String path) {
        requirePath(path);
        HttpResponse<String> r = send("DELETE", "/v1/" + kvMount + "/metadata/" + path, null, true);
        if (r.statusCode() != 404) {
            expect2xx(r, "destroy");
        }
    }

    /** Unauthenticated health probe; never throws. */
    public HealthState health() {
        try {
            HttpResponse<String> r = send("GET", "/v1/sys/health?standbyok=true&perfstandbyok=true", null, false);
            return switch (r.statusCode()) {
                case 200 -> HealthState.ACTIVE;
                case 429, 472, 473 -> HealthState.STANDBY;
                case 501 -> HealthState.UNINITIALIZED;
                case 503 -> HealthState.SEALED;
                default -> HealthState.UNREACHABLE;
            };
        } catch (RuntimeException e) {
            return HealthState.UNREACHABLE;
        }
    }

    // ---------------------------------------------------------------------------------------------- internals

    private HttpResponse<String> send(String method, String path, String body, boolean authenticated) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(address.resolve(path)).timeout(requestTimeout)
                    .header("Content-Type", "application/json");
            if (authenticated) {
                char[] t = token().reveal(); // semgrep-justified: Vault token header
                try {
                    b.header("X-Vault-Token", new String(t));
                } finally {
                    Arrays.fill(t, '\0');
                }
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (authenticated && r.statusCode() == 403) {
                invalidateToken(); // expired/revoked token: next call logs in again
            }
            return r;
        } catch (IOException e) {
            throw IamException.secretsUnavailable(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw IamException.secretsUnavailable(e);
        } catch (IllegalArgumentException e) {
            throw IamException.secretsUnavailable(e);
        }
    }

    private synchronized Secret token() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt)) {
            return token;
        }
        AppRoleCredentials c;
        try {
            c = credentials.get();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Vault AppRole credentials unavailable");
            throw IamException.secretsUnavailable(e);
        }
        char[] sid = c.secretId().reveal(); // semgrep-justified: AppRole login body
        HttpResponse<String> r;
        try {
            r = send("POST", "/v1/auth/approle/login", Json.write(Map.of("role_id", c.roleId(), "secret_id", new String(sid))), false);
        } finally {
            Arrays.fill(sid, '\0');
        }
        expect2xx(r, "login");
        Map<String, Object> json = Json.parseObject(r.body());
        Object clientToken = Json.path(json, "auth", "client_token");
        Object lease = Json.path(json, "auth", "lease_duration");
        if (!(clientToken instanceof String t) || !(lease instanceof BigDecimal l)) {
            throw IamException.secretsUnavailable(null);
        }
        if (token != null) {
            token.destroy();
        }
        token = Secret.of(t);
        long seconds = Math.max(l.longValue() - 60, 30);
        tokenExpiresAt = now.plusSeconds(seconds);
        return token;
    }

    private synchronized void invalidateToken() {
        tokenExpiresAt = Instant.EPOCH;
    }

    private static void expect2xx(HttpResponse<String> r, String action) {
        if (r.statusCode() / 100 != 2) {
            // Never include the response body: it may echo request data.
            LOG.log(Level.WARNING, "Vault {0} failed with HTTP {1}", new Object[]{action, r.statusCode()});
            throw IamException.secretsUnavailable(null);
        }
    }

    private static void requirePath(String path) {
        if (path == null || !PATH.matcher(path).matches() || path.contains("..") || path.contains("//")) {
            throw new IllegalArgumentException("Invalid secret path");
        }
    }
}
