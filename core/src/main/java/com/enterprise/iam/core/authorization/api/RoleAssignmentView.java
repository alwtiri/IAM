package com.enterprise.iam.core.authorization.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleAssignmentView(UUID id, UUID identityId, UUID roleId, String roleCode, List<ScopeElementView> scope,
                                 String source, String status, Instant validFrom, Instant validUntil, UUID grantedBy,
                                 Instant grantedAt, UUID revokedBy, Instant revokedAt, String reason) {

    public record ScopeElementView(String type, String value) {
    }
}
