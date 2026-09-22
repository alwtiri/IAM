package com.enterprise.iam.core.operation.api;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A provider operation to run on the worker plane (contracts/messaging/operation-command.schema.json).
 *
 * @param operation         ProviderOperation name, e.g. DISCOVER_ACCOUNTS, DISABLE_ACCOUNT
 * @param mutating          true if the operation changes the target; SUCCESS then requires verification (G5)
 * @param payload           non-secret arguments; secret-like keys are rejected
 * @param idempotencyKey    stable key for the business intent (same intent → same operation)
 */
public record ProviderCommand(String operation, boolean mutating, String providerType, UUID providerInstanceId, UUID targetId,
                              UUID accountId, UUID requesterId, String scopeOrgPath, String scopeEnvironment,
                              Map<String, Object> payload, Duration timeout, int maxAttempts, String idempotencyKey) {

    public ProviderCommand {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (!providerType.matches("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")) {
            throw new IllegalArgumentException("invalid provider type");
        }
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("idempotencyKey must be 8..128 characters");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        for (String k : payload.keySet()) {
            String l = k.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("password") || l.contains("secret") || l.contains("token") || l.contains("privatekey")) {
                throw new IllegalArgumentException("secret-like payload key '" + k + "' is not allowed; use a credential handle");
            }
        }
        timeout = timeout == null ? Duration.ofMinutes(2) : timeout;
        maxAttempts = maxAttempts < 1 ? 3 : maxAttempts;
    }

    /** Queue (and routing key) of the provider type, e.g. {@code ops.linux-ssh} (WORKER-ISOLATION §2). */
    public String queue() {
        return "ops." + providerType;
    }
}
