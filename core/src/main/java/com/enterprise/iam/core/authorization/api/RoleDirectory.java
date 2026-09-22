package com.enterprise.iam.core.authorization.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Role information and request fulfilment for other modules (request, sod). No authorization check: callers enforce
 * their own rules; grants made here are recorded as source REQUEST by the system identity.
 */
public interface RoleDirectory {

    List<RoleView> allRoles();

    Optional<RoleView> findRole(UUID roleId);

    /** Codes of the roles the identity holds effectively now (active, within validity, identity ACTIVE). */
    List<String> activeRoleCodes(UUID identityId);

    /** ACTIVE identities that effectively hold the role now. */
    List<UUID> activeHolders(String roleCode);

    /**
     * Grants a role for an approved access request, time-bound.
     *
     * @param scopeType {@code GLOBAL} or {@code ORG_UNIT}
     * @return the role assignment id
     */
    UUID grantForRequest(UUID identityId, UUID roleId, String scopeType, String scopeValue, Instant validUntil, UUID requestId, String reason);
}
