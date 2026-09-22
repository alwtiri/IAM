package com.enterprise.iam.core.authorization.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.authorization.api.EffectiveAccessView;
import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.authorization.api.RoleAssignmentView;
import com.enterprise.iam.core.authorization.api.RoleDirectory;
import com.enterprise.iam.core.authorization.api.RoleView;
import com.enterprise.iam.core.authorization.domain.AssignmentScope;
import com.enterprise.iam.core.authorization.domain.Role;
import com.enterprise.iam.core.authorization.domain.RoleAssignment;
import com.enterprise.iam.core.authorization.domain.ScopeElement;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.BootstrapAdministratorGrant;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Role assignment use cases (spec §19, F6–F7) with privilege-escalation protection (threat T13):
 * <ul>
 *   <li>no self-assignment and no self-revocation;</li>
 *   <li>the grantor needs {@code role-assignment:write} covering the beneficiary <em>and</em> every org element of the
 *       new scope, and must itself hold every permission of the role within that scope;</li>
 *   <li>GLOBAL scope, or scopes without an org dimension, can only be granted by GLOBAL holders;</li>
 *   <li>the last effective GLOBAL Platform Administrator cannot be revoked (lock-out protection).</li>
 * </ul>
 */
public class RoleAssignmentService implements BootstrapAdministratorGrant, RoleDirectory {

    public record GrantCommand(UUID identityId, UUID roleId, Set<ScopeElement> scope, Instant validFrom, Instant validUntil, String reason) {
    }

    private final AuthorizationStore store;
    private final IdentityDirectory identities;
    private final OrganizationDirectory org;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final DomainEventPublisher events;
    private final TransactionRunner tx;
    private final Clock clock;

    public RoleAssignmentService(AuthorizationStore store, IdentityDirectory identities, OrganizationDirectory org, AccessGuard guard,
                                 AuditRecorder audit, DomainEventPublisher events, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.identities = identities;
        this.org = org;
        this.guard = guard;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public RoleAssignmentView grant(CurrentActor actor, GrantCommand cmd) {
        return tx.inTransaction(() -> {
            IdentitySummary beneficiary = identities.find(cmd.identityId())
                    .orElseThrow(() -> IamException.validation("identityId", "NOT_FOUND", "identity does not exist"));
            guard.require(actor, Permissions.ROLE_ASSIGNMENT_WRITE, scopeOf(beneficiary), false);
            if (actor.identityId().equals(cmd.identityId())) {
                audit.record(actor, AuditEntry.denied("role-assignment.granted", "identity", cmd.identityId(), "self-assignment"));
                throw IamException.accessDenied();
            }
            if ("DISABLED".equals(beneficiary.state()) || "ARCHIVED".equals(beneficiary.state())) {
                throw IamException.validation("identityId", "INACTIVE", "roles cannot be assigned to a " + beneficiary.state() + " identity");
            }
            Role role = store.role(cmd.roleId()).orElseThrow(() -> IamException.validation("roleId", "NOT_FOUND", "role does not exist"));
            AssignmentScope scope = new AssignmentScope(cmd.scope());
            requireGrantableScope(actor, role, scope);
            RoleAssignment a = RoleAssignment.grant(Ids.newId(clock), cmd.identityId(), role.id(), scope, RoleAssignment.Source.DIRECT,
                    cmd.validFrom(), cmd.validUntil(), actor.identityId(), clock.instant(), cmd.reason());
            if (store.activeDuplicateExists(a)) {
                throw IamException.alreadyExists("An identical active assignment");
            }
            store.insert(a);
            audit.record(actor, AuditEntry.success("role-assignment.granted", "role-assignment", a.id(),
                    Map.of("identityId", a.identityId().toString(), "role", role.code(), "scope", describe(scope),
                            "validUntil", String.valueOf(a.validUntil()))));
            events.publish(new RoleAssignmentChanged(a.id(), a.identityId(), role.code(), "GRANTED", actor.identityId()));
            return view(a, role);
        });
    }

    public RoleAssignmentView revoke(CurrentActor actor, UUID assignmentId, String reason) {
        return tx.inTransaction(() -> {
            RoleAssignment a = store.assignment(assignmentId).orElseThrow(() -> IamException.notFound("Role assignment"));
            IdentitySummary beneficiary = identities.find(a.identityId()).orElseThrow(() -> IamException.notFound("Role assignment"));
            guard.require(actor, Permissions.ROLE_ASSIGNMENT_WRITE, scopeOf(beneficiary), true);
            if (actor.identityId().equals(a.identityId())) {
                throw IamException.accessDenied();
            }
            if (reason == null || reason.isBlank()) {
                throw IamException.validation("reason", "REQUIRED", "a reason is required");
            }
            Role role = store.role(a.roleId()).orElseThrow();
            Instant now = clock.instant();
            if (Role.PLATFORM_ADMINISTRATOR.equals(role.code()) && a.scope().isGlobal() && a.isEffective(now)
                    && store.countEffectiveGlobal(role.id(), now) <= 1) {
                throw IamException.invalidTransition("Role assignment", "last GLOBAL platform administrator", "REVOKED");
            }
            RoleAssignment revoked = a.revoke(actor.identityId(), now, reason);
            if (!store.update(revoked, a.version())) {
                throw IamException.concurrentModification("Role assignment");
            }
            audit.record(actor, AuditEntry.success("role-assignment.revoked", "role-assignment", a.id(),
                    Map.of("identityId", a.identityId().toString(), "role", role.code(), "reason", reason)));
            events.publish(new RoleAssignmentChanged(a.id(), a.identityId(), role.code(), "REVOKED", actor.identityId()));
            return view(revoked, role);
        });
    }

    public PageResult<RoleAssignmentView> listFor(CurrentActor actor, UUID identityId, boolean activeOnly, PageRequest page) {
        IdentitySummary beneficiary = identities.find(identityId).orElseThrow(() -> IamException.notFound("Identity"));
        guard.require(actor, Permissions.ROLE_ASSIGNMENT_READ, scopeOf(beneficiary), true);
        Map<UUID, Role> roles = tx.readOnly(store::roles).stream().collect(Collectors.toMap(Role::id, r -> r));
        return tx.readOnly(() -> PageResult.fromOverfetch(store.assignmentsOf(identityId, activeOnly, page), page.limit(), RoleAssignment::id))
                .map(a -> view(a, roles.get(a.roleId())));
    }

    public List<RoleView> roles(CurrentActor actor) {
        if (!guard.holdsAnywhere(actor, Permissions.ROLE_READ)) {
            throw IamException.accessDenied();
        }
        return tx.readOnly(store::roles).stream().sorted(Comparator.comparing(Role::code)).map(RoleAssignmentService::view).toList();
    }

    public RoleView role(CurrentActor actor, UUID id) {
        if (!guard.holdsAnywhere(actor, Permissions.ROLE_READ)) {
            throw IamException.accessDenied();
        }
        return view(tx.readOnly(() -> store.role(id)).orElseThrow(() -> IamException.notFound("Role")));
    }

    public List<String> permissions(CurrentActor actor) {
        if (!guard.holdsAnywhere(actor, Permissions.ROLE_READ)) {
            throw IamException.accessDenied();
        }
        return tx.readOnly(store::permissionCatalog);
    }

    /** Effective access of the caller; always allowed for the authenticated actor. */
    public EffectiveAccessView me(CurrentActor actor) {
        IdentitySummary self = identities.find(actor.identityId()).orElseThrow(IamException::accessDenied);
        Map<UUID, Role> roles = tx.readOnly(store::roles).stream().collect(Collectors.toMap(Role::id, r -> r));
        Instant now = clock.instant();
        List<EffectiveAccessView.Grant> grants = tx.readOnly(() -> store.assignmentsOf(actor.identityId(), true, new PageRequest(200, null)))
                .stream().filter(a -> a.isEffective(now))
                .map(a -> {
                    Role r = roles.get(a.roleId());
                    return new EffectiveAccessView.Grant(r.code(), r.permissions().stream().sorted().toList(), scopeView(a.scope()), a.validUntil());
                }).toList();
        return new EffectiveAccessView(self.id(), self.username(), self.displayName(), actor.acr(), actor.authTime(), grants);
    }

    /** Marks expired assignments (S11); decisions already ignore them, this keeps state and audit accurate. */
    public int expireDue(int batchSize) {
        CurrentActor system = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            List<RoleAssignment> expired = store.findExpired(now, batchSize);
            for (RoleAssignment a : expired) {
                RoleAssignment e = a.expire(now);
                if (store.update(e, a.version())) {
                    String code = store.role(a.roleId()).map(Role::code).orElse("?");
                    audit.record(system, AuditEntry.success("role-assignment.expired", "role-assignment", a.id(),
                            Map.of("identityId", a.identityId().toString(), "role", code)));
                    events.publish(new RoleAssignmentChanged(a.id(), a.identityId(), code, "EXPIRED", system.identityId()));
                }
            }
            return expired.size();
        });
    }

    /** Bootstrap only: called inside the bootstrap transaction of the identity module (ADR-0016). */
    @Override
    public void grantPlatformAdministrator(UUID identityId) {
        Role role = store.roleByCode(Role.PLATFORM_ADMINISTRATOR).orElseThrow(() -> new IllegalStateException("seed role missing"));
        Instant now = clock.instant();
        store.insert(RoleAssignment.grant(Ids.newId(clock), identityId, role.id(), AssignmentScope.global(),
                RoleAssignment.Source.BOOTSTRAP, now, null, SystemIdentities.SYSTEM_IDENTITY_ID, now, "platform bootstrap"));
    }

    private void requireGrantableScope(CurrentActor actor, Role role, AssignmentScope scope) {
        if (scope.isGlobal() || !scope.hasOrg()) {
            // GLOBAL, or scopes limited only by environment/provider/target: GLOBAL grantors only (fail closed)
            requireAll(actor, role, ResourceScope.PLATFORM);
            return;
        }
        for (ScopeElement e : scope.orgElements()) {
            String path = org.orgUnitPath(UUID.fromString(e.value()))
                    .orElseThrow(() -> IamException.validation("scope", "NOT_FOUND", "org unit " + e.value() + " does not exist"));
            requireAll(actor, role, ResourceScope.orgUnit(path));
        }
    }

    private void requireAll(CurrentActor actor, Role role, ResourceScope where) {
        if (!guard.isAllowed(actor, Permissions.ROLE_ASSIGNMENT_WRITE, where)) {
            throw IamException.accessDenied();
        }
        for (String p : role.permissions()) {
            if (!guard.isAllowed(actor, p, where)) {
                throw new IamException(com.enterprise.iam.kernel.ErrorCode.ACCESS_DENIED,
                        "You cannot grant a role containing permission " + p + " that you do not hold in this scope");
            }
        }
    }

    // ------------------------------------------------------------------ RoleDirectory (request fulfilment, SoD)

    @Override
    public List<RoleView> allRoles() {
        return tx.readOnly(() -> store.roles().stream().map(RoleAssignmentService::view).toList());
    }

    @Override
    public Optional<RoleView> findRole(UUID roleId) {
        return tx.readOnly(() -> store.role(roleId).map(RoleAssignmentService::view));
    }

    @Override
    public List<String> activeRoleCodes(UUID identityId) {
        return tx.readOnly(() -> store.activeRoleCodes(identityId, clock.instant()));
    }

    @Override
    public List<UUID> activeHolders(String roleCode) {
        return tx.readOnly(() -> store.activeHolders(roleCode, clock.instant()));
    }

    @Override
    public UUID grantForRequest(UUID identityId, UUID roleId, String scopeType, String scopeValue, Instant validUntil, UUID requestId,
                                String reason) {
        CurrentActor system = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);
        return tx.inTransaction(() -> {
            IdentitySummary beneficiary = identities.find(identityId)
                    .orElseThrow(() -> IamException.validation("identityId", "NOT_FOUND", "identity does not exist"));
            if (!"ACTIVE".equals(beneficiary.state())) {
                throw IamException.validation("identityId", "INACTIVE", "roles are only granted to ACTIVE identities");
            }
            Role role = store.role(roleId).orElseThrow(() -> IamException.validation("roleId", "NOT_FOUND", "role does not exist"));
            ScopeElement element = "GLOBAL".equals(scopeType) ? new ScopeElement(ScopeElement.Type.GLOBAL, "*")
                    : new ScopeElement(ScopeElement.Type.ORG_UNIT, scopeValue);
            Instant now = clock.instant();
            RoleAssignment a = RoleAssignment.grant(Ids.newId(clock), identityId, role.id(), new AssignmentScope(Set.of(element)),
                    RoleAssignment.Source.REQUEST, now, validUntil, SystemIdentities.SYSTEM_IDENTITY_ID, now, reason);
            if (store.activeDuplicateExists(a)) {
                throw IamException.alreadyExists("An identical active assignment");
            }
            store.insert(a);
            audit.record(system, AuditEntry.success("role-assignment.granted", "role-assignment", a.id(),
                    Map.of("identityId", identityId.toString(), "role", role.code(), "scope", describe(a.scope()),
                            "validUntil", String.valueOf(validUntil), "source", "REQUEST", "request", requestId.toString())));
            events.publish(new RoleAssignmentChanged(a.id(), identityId, role.code(), "GRANTED", system.identityId()));
            return a.id();
        });
    }

    static ResourceScope scopeOf(IdentitySummary s) {
        return s.orgUnitPath() == null ? ResourceScope.PLATFORM : ResourceScope.orgUnit(s.orgUnitPath());
    }

    static String describe(AssignmentScope s) {
        return s.elements().stream().map(e -> e.type() + ":" + e.value()).sorted().collect(Collectors.joining(","));
    }

    static List<RoleAssignmentView.ScopeElementView> scopeView(AssignmentScope s) {
        return s.elements().stream().map(e -> new RoleAssignmentView.ScopeElementView(e.type().name(), e.value()))
                .sorted(Comparator.comparing(RoleAssignmentView.ScopeElementView::type).thenComparing(RoleAssignmentView.ScopeElementView::value))
                .toList();
    }

    static RoleAssignmentView view(RoleAssignment a, Role role) {
        return new RoleAssignmentView(a.id(), a.identityId(), a.roleId(), role == null ? null : role.code(), scopeView(a.scope()),
                a.source().name(), a.status().name(), a.validFrom(), a.validUntil(), a.grantedBy(), a.grantedAt(), a.revokedBy(),
                a.revokedAt(), a.reason());
    }

    static RoleView view(Role r) {
        return new RoleView(r.id(), r.code(), r.name(), r.description(), r.builtIn(), r.permissions().stream().sorted().toList());
    }
}
