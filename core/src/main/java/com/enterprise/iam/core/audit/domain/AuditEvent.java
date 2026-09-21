package com.enterprise.iam.core.audit.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable audit event including its position in the hash chain. {@code occurredAt} is truncated to microseconds,
 * the precision of PostgreSQL {@code timestamptz}, so the hash computed before insert equals the one recomputed from
 * the stored row.
 */
public record AuditEvent(UUID id, String chainPartition, long seq, Instant occurredAt, UUID actorIdentityId,
                         String actorType, String action, String objectType, String objectId, UUID targetId,
                         String source, String result, String reason, String correlationId, String ip,
                         UUID providerInstanceId, Map<String, String> details, byte[] prevHash, byte[] hash) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chainPartition, "chainPartition");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(result, "result");
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        details = details == null ? Map.of() : Map.copyOf(new TreeMap<>(details));
        prevHash = prevHash == null ? null : prevHash.clone();
        hash = hash == null ? null : hash.clone();
    }

    public AuditEvent withChain(long newSeq, byte[] newPrevHash, byte[] newHash) {
        return new AuditEvent(id, chainPartition, newSeq, occurredAt, actorIdentityId, actorType, action, objectType,
                objectId, targetId, source, result, reason, correlationId, ip, providerInstanceId, details, newPrevHash, newHash);
    }

    @Override
    public byte[] prevHash() {
        return prevHash == null ? null : prevHash.clone();
    }

    @Override
    public byte[] hash() {
        return hash == null ? null : hash.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AuditEvent other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
