package com.enterprise.iam.core.audit.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read model of an audit event (spec §49 fields plus chain position). */
public record AuditEventView(UUID id, long seq, Instant occurredAt, UUID actorIdentityId, String actorType,
                             String action, String objectType, String objectId, UUID targetId, String source,
                             String result, String reason, String correlationId, String ip, UUID providerInstanceId,
                             Map<String, String> details, String hash) {
}
