package com.enterprise.iam.core.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.authorization.api.RoleAssignmentView;
import com.enterprise.iam.core.authorization.application.RoleAssignmentService.GrantCommand;
import com.enterprise.iam.core.authorization.domain.AssignmentScope;
import com.enterprise.iam.core.authorization.domain.RoleAssignment;
import com.enterprise.iam.core.authorization.domain.ScopeElement;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.IamException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoleAssignmentServiceTest {

    private final AuthorizationFixture f = new AuthorizationFixture();

    private static Set<ScopeElement> tree(UUID orgUnit) {
        return Set.of(new ScopeElement(ScopeElement.Type.ORG_UNIT_TREE, orgUnit.toString()));
    }

    @Test
    void globalAdminCanDelegateDepartmentAdministration() {
        UUID admin = f.identity(null, "ACTIVE");
        f.assign(admin, f.platformAdmin, AssignmentScope.global());
        UUID alice = f.identity(f.deptA, "ACTIVE");
        RoleAssignmentView v = f.service.grant(f.actor(admin), new GrantCommand(alice, f.iamAdmin.id(), tree(f.deptA), null, null, "delegation"));
        assertEquals("ACTIVE", v.status());
        assertTrue(f.guard.isAllowed(f.actor(alice), Permissions.IDENTITY_WRITE, ResourceScope.orgUnit(f.paths.get(f.deptA))));
        assertFalse(f.guard.isAllowed(f.actor(alice), Permissions.IDENTITY_WRITE, ResourceScope.orgUnit(f.paths.get(f.deptB))));
        assertTrue(f.events.get(0) instanceof RoleAssignmentChanged);
        assertEquals("role-assignment.granted", f.audit.get(0).action());
    }

    @Test
    void selfAssignmentIsDeniedAndAudited() {
        UUID admin = f.identity(null, "ACTIVE");
        f.assign(admin, f.platformAdmin, AssignmentScope.global());
        IamException e = assertThrows(IamException.class, () -> f.service.grant(f.actor(admin),
                new GrantCommand(admin, f.auditor.id(), Set.of(ScopeElement.GLOBAL), null, null, null)));
        assertEquals(ErrorCode.ACCESS_DENIED, e.code());
        assertEquals("DENIED", f.audit.get(0).result().name());
    }

    @Test
    void departmentAdminCannotEscalateOrLeaveTheirScope() {
        UUID deptAdmin = f.identity(f.deptA, "ACTIVE");
        f.assign(deptAdmin, f.iamAdmin, new AssignmentScope(tree(f.deptA)));
        UUID bob = f.identity(f.deptA, "ACTIVE");
        UUID carol = f.identity(f.deptB, "ACTIVE");

        // role containing permissions the grantor does not hold (audit:*) → denied
        assertThrows(IamException.class, () -> f.service.grant(f.actor(deptAdmin), new GrantCommand(bob, f.auditor.id(), tree(f.deptA), null, null, null)));
        // GLOBAL scope → denied
        assertThrows(IamException.class, () -> f.service.grant(f.actor(deptAdmin), new GrantCommand(bob, f.iamAdmin.id(), Set.of(ScopeElement.GLOBAL), null, null, null)));
        // scope covering another department → denied
        assertThrows(IamException.class, () -> f.service.grant(f.actor(deptAdmin), new GrantCommand(bob, f.iamAdmin.id(), tree(f.deptB), null, null, null)));
        // beneficiary in another department → denied
        assertThrows(IamException.class, () -> f.service.grant(f.actor(deptAdmin), new GrantCommand(carol, f.iamAdmin.id(), tree(f.deptA), null, null, null)));
        // environment-only scope (no org dimension) requires GLOBAL grantor
        assertThrows(IamException.class, () -> f.service.grant(f.actor(deptAdmin), new GrantCommand(bob, f.iamAdmin.id(),
                Set.of(new ScopeElement(ScopeElement.Type.ENVIRONMENT, "TEST")), null, null, null)));
        // within scope and without escalation → allowed
        assertEquals("ACTIVE", f.service.grant(f.actor(deptAdmin), new GrantCommand(bob, f.iamAdmin.id(), tree(f.deptA), null, null, null)).status());
    }

    @Test
    void lastGlobalPlatformAdministratorCannotBeRevoked() {
        UUID admin1 = f.identity(null, "ACTIVE");
        UUID admin2 = f.identity(null, "ACTIVE");
        RoleAssignment a1 = f.assign(admin1, f.platformAdmin, AssignmentScope.global());
        RoleAssignment a2 = f.assign(admin2, f.platformAdmin, AssignmentScope.global());
        UUID iam = f.identity(null, "ACTIVE");
        f.assign(iam, f.iamAdmin, AssignmentScope.global());

        f.service.revoke(f.actor(iam), a2.id(), "rotation"); // two admins → revoking one is fine
        IamException e = assertThrows(IamException.class, () -> f.service.revoke(f.actor(iam), a1.id(), "remove last"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code());
        IamException self = assertThrows(IamException.class, () -> f.service.revoke(f.actor(admin1), a1.id(), "self"));
        assertEquals(ErrorCode.ACCESS_DENIED, self.code());
    }

    @Test
    void outOfScopeObjectsAreReportedAsNotFound() {
        UUID deptAdmin = f.identity(f.deptA, "ACTIVE");
        f.assign(deptAdmin, f.iamAdmin, new AssignmentScope(tree(f.deptA)));
        UUID carol = f.identity(f.deptB, "ACTIVE");
        RoleAssignment c = f.assign(carol, f.auditor, new AssignmentScope(tree(f.deptB)));
        IamException e = assertThrows(IamException.class, () -> f.service.revoke(f.actor(deptAdmin), c.id(), "x"));
        assertEquals(ErrorCode.NOT_FOUND, e.code());
    }

    @Test
    void expiredAssignmentsStopGrantingImmediatelyAndAreSwept() {
        UUID admin = f.identity(null, "ACTIVE");
        f.assign(admin, f.platformAdmin, AssignmentScope.global());
        UUID temp = f.identity(f.deptA, "ACTIVE");
        f.service.grant(f.actor(admin), new GrantCommand(temp, f.iamAdmin.id(), tree(f.deptA), null, f.clock.instant().plusSeconds(3600), null));
        ResourceScope a = ResourceScope.orgUnit(f.paths.get(f.deptA));
        assertTrue(f.guard.isAllowed(f.actor(temp), Permissions.IDENTITY_READ, a));
        f.clock.advanceSeconds(3601);
        assertFalse(f.guard.isAllowed(f.actor(temp), Permissions.IDENTITY_READ, a), "enforced at decision time");
        assertEquals(1, f.service.expireDue(100));
        assertEquals(0, f.service.expireDue(100));
    }

    @Test
    void inactiveIdentitiesHoldNoPermissions() {
        UUID s = f.identity(null, "SUSPENDED");
        f.assign(s, f.platformAdmin, AssignmentScope.global());
        assertFalse(f.guard.holdsAnywhere(f.actor(s), Permissions.SYSTEM_READ));
    }

    @Test
    void bootstrapGrantsGlobalPlatformAdministrator() {
        UUID first = f.identity(null, "ACTIVE");
        f.service.grantPlatformAdministrator(first);
        assertTrue(f.guard.isAllowed(f.actor(first), Permissions.ROLE_ASSIGNMENT_WRITE, ResourceScope.PLATFORM));
    }

    @Test
    void scopeValidation() {
        assertThrows(IamException.class, () -> new AssignmentScope(Set.of()));
        assertThrows(IamException.class, () -> new AssignmentScope(Set.of(ScopeElement.GLOBAL, new ScopeElement(ScopeElement.Type.ENVIRONMENT, "TEST"))));
        assertThrows(IamException.class, () -> new ScopeElement(ScopeElement.Type.ENVIRONMENT, "MARS"));
        assertThrows(IamException.class, () -> new ScopeElement(ScopeElement.Type.ORG_UNIT, "not-a-uuid"));
        assertThrows(IamException.class, () -> new ScopeElement(ScopeElement.Type.GLOBAL, "x"));
    }

    @Test
    void deletedOrgUnitGrantsNothing() {
        UUID a = f.identity(f.deptA, "ACTIVE");
        f.assign(a, f.iamAdmin, new AssignmentScope(tree(f.deptB)));
        String bPath = f.paths.remove(f.deptB);
        assertFalse(f.guard.isAllowed(f.actor(a), Permissions.IDENTITY_READ, ResourceScope.orgUnit(bPath)));
        assertTrue(f.guard.filter(f.actor(a), Permissions.IDENTITY_READ).isEmpty());
    }
}
