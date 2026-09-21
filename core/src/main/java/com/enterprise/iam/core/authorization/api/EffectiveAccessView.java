package com.enterprise.iam.core.authorization.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response of {@code GET /api/v1/me}. */
public record EffectiveAccessView(UUID identityId, String username, String displayName, String authenticationContext,
                                  Instant authenticatedAt, List<Grant> grants) {

    public record Grant(String roleCode, List<String> permissions, List<RoleAssignmentView.ScopeElementView> scope, Instant validUntil) {
    }
}
