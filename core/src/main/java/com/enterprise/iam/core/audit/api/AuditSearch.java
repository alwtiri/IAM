package com.enterprise.iam.core.audit.api;

import java.time.Instant;
import java.util.UUID;

/** Audit query filter; null fields are not filtered. */
public record AuditSearch(UUID actorIdentityId, String action, String objectType, String objectId,
                          String correlationId, Instant from, Instant to) {
}
