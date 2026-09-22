package com.enterprise.iam.core.authorization;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.authorization.application.AccessGuardService;
import com.enterprise.iam.core.authorization.application.AuthorizationStore;
import com.enterprise.iam.core.authorization.application.RoleAssignmentService;
import com.enterprise.iam.core.authorization.domain.AssignmentScope;
import com.enterprise.iam.core.authorization.domain.AuthorizationEngine;
import com.enterprise.iam.core.authorization.domain.Role;
import com.enterprise.iam.core.authorization.domain.RoleAssignment;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** In-memory authorization world: org tree root → dept A / dept B, identities, roles, assignments. */
final class AuthorizationFixture {

    final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-09-21T10:00:00Z"));
    final UUID root = Ids.newId();
    final UUID deptA = Ids.newId();
    final UUID deptB = Ids.newId();
    final Map<UUID, String> paths = new HashMap<>();
    final Map<UUID, IdentitySummary> identities = new HashMap<>();
    final Map<UUID, Role> roles = new LinkedHashMap<>();
    final Map<UUID, RoleAssignment> assignments = new LinkedHashMap<>();
    final List<AuditEntry> audit = new ArrayList<>();
    final List<Object> events = new ArrayList<>();

    final Role platformAdmin = role(Role.PLATFORM_ADMINISTRATOR, Set.copyOf(Permissions.ALL));
    final Role iamAdmin = role("IAM_ADMINISTRATOR", Set.of(Permissions.IDENTITY_READ, Permissions.IDENTITY_WRITE,
            Permissions.ROLE_READ, Permissions.ROLE_ASSIGNMENT_READ, Permissions.ROLE_ASSIGNMENT_WRITE));
    final Role auditor = role("AUDITOR", Set.of(Permissions.AUDIT_READ, Permissions.AUDIT_VERIFY, Permissions.IDENTITY_READ));

    final AuthorizationStore store = new MemoryStore();
    final OrganizationDirectory org = new OrganizationDirectory() {
        @Override
        public Optional<String> orgUnitPath(UUID id) {
            return Optional.ofNullable(paths.get(id));
        }

        @Override
        public boolean positionExists(UUID id) {
            return false;
        }

        @Override
        public boolean locationExists(UUID id) {
            return false;
        }
    };
    final IdentityDirectory directory = id -> Optional.ofNullable(identities.get(id));
    final AccessGuardService guard;
    final RoleAssignmentService service;

    AuthorizationFixture() {
        paths.put(root, "/" + root + "/");
        paths.put(deptA, "/" + root + "/" + deptA + "/");
        paths.put(deptB, "/" + root + "/" + deptB + "/");
        guard = new AccessGuardService(store, org, clock);
        AuditRecorder recorder = (actor, entry) -> audit.add(entry);
        service = new RoleAssignmentService(store, directory, org, guard, recorder, events::add, TestSupport.DIRECT_TX, clock);
    }

    Role role(String code, Set<String> permissions) {
        Role r = new Role(Ids.newId(), code, code, null, true, permissions);
        roles.put(r.id(), r);
        return r;
    }

    UUID identity(UUID orgUnit, String state) {
        UUID id = Ids.newId();
        identities.put(id, new IdentitySummary(id, Ids.newId(), "u" + id.toString().substring(0, 8), "User", "u@example.org",
                "EMPLOYEE", state, null, orgUnit, orgUnit == null ? null : paths.get(orgUnit)));
        return id;
    }

    RoleAssignment assign(UUID identityId, Role role, AssignmentScope scope) {
        RoleAssignment a = RoleAssignment.grant(Ids.newId(), identityId, role.id(), scope, RoleAssignment.Source.DIRECT, null, null,
                null, clock.instant(), null);
        assignments.put(a.id(), a);
        return a;
    }

    CurrentActor actor(UUID identityId) {
        return TestSupport.actor(identityId);
    }

    final class MemoryStore implements AuthorizationStore {
        @Override
        public List<AuthorizationEngine.EffectiveGrant> effectiveGrants(UUID identityId, Instant now) {
            IdentitySummary s = identities.get(identityId);
            if (s == null || !"ACTIVE".equals(s.state())) {
                return List.of();
            }
            return assignments.values().stream().filter(a -> a.identityId().equals(identityId) && a.isEffective(now))
                    .map(a -> new AuthorizationEngine.EffectiveGrant(roles.get(a.roleId()).permissions(), a.scope())).toList();
        }

        @Override
        public List<String> permissionCatalog() {
            return Permissions.ALL;
        }

        @Override
        public List<Role> roles() {
            return List.copyOf(roles.values());
        }

        @Override
        public Optional<Role> role(UUID id) {
            return Optional.ofNullable(roles.get(id));
        }

        @Override
        public Optional<Role> roleByCode(String code) {
            return roles.values().stream().filter(r -> r.code().equals(code)).findFirst();
        }

        @Override
        public void insert(RoleAssignment a) {
            assignments.put(a.id(), a);
        }

        @Override
        public boolean update(RoleAssignment a, long expectedVersion) {
            RoleAssignment cur = assignments.get(a.id());
            if (cur == null || cur.version() != expectedVersion) {
                return false;
            }
            assignments.put(a.id(), new RoleAssignment(a.id(), a.identityId(), a.roleId(), a.scope(), a.source(), a.status(),
                    a.validFrom(), a.validUntil(), a.grantedBy(), a.grantedAt(), a.revokedBy(), a.revokedAt(), a.reason(), expectedVersion + 1));
            return true;
        }

        @Override
        public Optional<RoleAssignment> assignment(UUID id) {
            return Optional.ofNullable(assignments.get(id));
        }

        @Override
        public List<RoleAssignment> assignmentsOf(UUID identityId, boolean activeOnly, PageRequest page) {
            return assignments.values().stream().filter(a -> a.identityId().equals(identityId)
                    && (!activeOnly || a.status() == RoleAssignment.Status.ACTIVE)).toList();
        }

        @Override
        public boolean activeDuplicateExists(RoleAssignment c) {
            return assignments.values().stream().anyMatch(a -> a.status() == RoleAssignment.Status.ACTIVE
                    && a.identityId().equals(c.identityId()) && a.roleId().equals(c.roleId()) && a.scope().equals(c.scope()));
        }

        @Override
        public long countEffectiveGlobal(UUID roleId, Instant now) {
            return assignments.values().stream().filter(a -> a.roleId().equals(roleId) && a.scope().isGlobal() && a.isEffective(now)
                    && "ACTIVE".equals(identities.get(a.identityId()).state())).count();
        }

        @Override
        public List<RoleAssignment> findExpired(Instant now, int limit) {
            return assignments.values().stream().filter(a -> a.status() == RoleAssignment.Status.ACTIVE && a.validUntil() != null
                    && !now.isBefore(a.validUntil())).limit(limit).toList();
        }

        @Override
        public List<String> activeRoleCodes(UUID identityId, Instant now) {
            IdentitySummary s = identities.get(identityId);
            if (s == null || !"ACTIVE".equals(s.state())) {
                return List.of();
            }
            return assignments.values().stream().filter(a -> a.identityId().equals(identityId) && a.isEffective(now))
                    .map(a -> roles.get(a.roleId()).code()).distinct().sorted().toList();
        }

        @Override
        public List<UUID> activeHolders(String roleCode, Instant now) {
            return assignments.values().stream().filter(a -> a.isEffective(now) && roles.get(a.roleId()).code().equals(roleCode))
                    .map(RoleAssignment::identityId).filter(id -> identities.get(id) != null && "ACTIVE".equals(identities.get(id).state()))
                    .distinct().toList();
        }
    }
}
