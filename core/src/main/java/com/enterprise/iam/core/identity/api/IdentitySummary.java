package com.enterprise.iam.core.identity.api;

import java.time.Instant;
import java.util.UUID;

/** Identity with the person attributes other modules need (display, notification, scope). */
public record IdentitySummary(UUID id, UUID personId, String username, String displayName, String email, String type,
                              String state, Instant validUntil, UUID orgUnitId, String orgUnitPath) {
}
