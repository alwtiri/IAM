package com.enterprise.iam.core.authorization.api;

import java.util.UUID;

/** Published in the same transaction when an assignment is granted, revoked, or expires. */
public record RoleAssignmentChanged(UUID assignmentId, UUID identityId, String roleCode, String change, UUID actorIdentityId) {
}
