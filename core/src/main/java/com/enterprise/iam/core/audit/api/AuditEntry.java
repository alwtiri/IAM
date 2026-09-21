package com.enterprise.iam.core.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * What a module records about an action (spec §49). The recorder adds actor, time, correlation, source, and the
 * hash-chain fields. {@code details} holds non-sensitive string values only — never secrets or tokens.
 */
public record AuditEntry(String action, String objectType, String objectId, UUID targetId, Result result,
                         String reason, UUID providerInstanceId, Map<String, String> details) {

    public enum Result { SUCCESS, FAILURE, DENIED }

    public AuditEntry {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(result, "result");
        if (!action.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")) {
            throw new IllegalArgumentException("action must be dotted lower-case, e.g. identity.created");
        }
        details = details == null ? Map.of() : Map.copyOf(new TreeMap<>(details));
        for (String key : details.keySet()) {
            String k = key.toLowerCase(java.util.Locale.ROOT);
            if (k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("privatekey")) {
                throw new IllegalArgumentException("Secret-like audit detail key '" + key + "' is not allowed");
            }
        }
    }

    public static AuditEntry success(String action, String objectType, Object objectId, Map<String, String> details) {
        return new AuditEntry(action, objectType, objectId == null ? null : objectId.toString(), null, Result.SUCCESS,
                null, null, details);
    }

    public static AuditEntry denied(String action, String objectType, Object objectId, String reason) {
        return new AuditEntry(action, objectType, objectId == null ? null : objectId.toString(), null, Result.DENIED,
                reason, null, Map.of());
    }
}
