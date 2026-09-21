package com.enterprise.iam.core.authorization.application;

import com.enterprise.iam.core.authorization.domain.AuthorizationEngine;
import com.enterprise.iam.core.authorization.domain.Role;
import com.enterprise.iam.core.authorization.domain.RoleAssignment;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationStore {

    /** Effective grants of an ACTIVE identity at {@code now}; empty for inactive identities. */
    List<AuthorizationEngine.EffectiveGrant> effectiveGrants(UUID identityId, Instant now);

    List<String> permissionCatalog();

    List<Role> roles();

    Optional<Role> role(UUID id);

    Optional<Role> roleByCode(String code);

    void insert(RoleAssignment assignment);

    boolean update(RoleAssignment assignment, long expectedVersion);

    Optional<RoleAssignment> assignment(UUID id);

    List<RoleAssignment> assignmentsOf(UUID identityId, boolean activeOnly, PageRequest page);

    boolean activeDuplicateExists(RoleAssignment candidate);

    /** Number of effective GLOBAL assignments of {@code roleId} held by ACTIVE identities (lock-out protection). */
    long countEffectiveGlobal(UUID roleId, Instant now);

    List<RoleAssignment> findExpired(Instant now, int limit);
}
