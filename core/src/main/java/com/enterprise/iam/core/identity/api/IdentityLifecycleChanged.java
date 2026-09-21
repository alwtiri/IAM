package com.enterprise.iam.core.identity.api;

import java.util.UUID;

/** Domain event published in the same transaction as an identity state change. */
public record IdentityLifecycleChanged(UUID identityId, String fromState, String toState, String reason, UUID actorIdentityId) {
}
