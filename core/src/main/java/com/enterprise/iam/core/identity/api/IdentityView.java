package com.enterprise.iam.core.identity.api;

import java.time.Instant;
import java.util.UUID;

public record IdentityView(UUID id, UUID personId, String displayName, String type, String username, String state,
                           String stateReason, Instant validFrom, Instant validUntil, boolean platformUser, long version) {
}
