package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.kernel.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Parsed operation command (contracts/messaging/operation-command.schema.json). Holds no secret material: credentials
 * are single-use handles redeemed just in time.
 */
public record Command(UUID messageId, UUID operationId, String idempotencyKey, String providerType, UUID providerInstanceId,
                      UUID targetId, String operation, int attempt, Instant deadline, String correlationId,
                      Map<String, String> credentialHandles, Map<String, Object> payload) {

    public Command {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(deadline, "deadline");
        credentialHandles = credentialHandles == null ? Map.of() : Map.copyOf(credentialHandles);
        payload = payload == null ? Map.of() : payload;
    }

    /** Non-secret connection details the Core put into the payload ({@code connection.endpoint}, {@code connection.settings}). */
    public String endpoint() {
        return payload.get("connection") instanceof Map<?, ?> c && c.get("endpoint") != null ? c.get("endpoint").toString() : null;
    }

    public Map<String, String> settings() {
        Map<String, String> out = new LinkedHashMap<>();
        if (payload.get("connection") instanceof Map<?, ?> c && c.get("settings") instanceof Map<?, ?> s) {
            s.forEach((k, v) -> out.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return out;
    }

    public static Command parse(String json) {
        Map<String, Object> m = Json.parseObject(json);
        if (!(m.get("schemaVersion") instanceof Number v) || v.intValue() != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
        Map<String, String> handles = new LinkedHashMap<>();
        if (m.get("credentialHandles") instanceof Map<?, ?> h) {
            h.forEach((k, val) -> handles.put(String.valueOf(k), String.valueOf(val)));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = m.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        return new Command(uuid(m.get("messageId")), uuid(m.get("operationId")), str(m.get("idempotencyKey")), str(m.get("providerType")),
                uuid(m.get("providerInstanceId")), m.get("targetId") == null ? null : uuid(m.get("targetId")), str(m.get("operation")),
                m.get("attempt") instanceof Number a ? a.intValue() : 1, Instant.parse(str(m.get("deadline"))),
                m.get("correlationId") == null ? "worker-" + UUID.randomUUID() : str(m.get("correlationId")), handles, payload);
    }

    private static UUID uuid(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("missing uuid");
        }
        return UUID.fromString(v.toString());
    }

    private static String str(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("missing field");
        }
        return v.toString();
    }
}
