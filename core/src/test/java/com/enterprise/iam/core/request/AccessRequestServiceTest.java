package com.enterprise.iam.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.authorization.api.RoleDirectory;
import com.enterprise.iam.core.authorization.api.RoleView;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.policy.domain.Policy;
import com.enterprise.iam.core.policy.domain.PolicyEvaluator;
import com.enterprise.iam.core.request.api.AccessRequestView;
import com.enterprise.iam.core.request.application.AccessRequestService;
import com.enterprise.iam.core.request.application.RequestStore;
import com.enterprise.iam.core.request.domain.AccessRequest;
import com.enterprise.iam.core.request.domain.ApprovalStep;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.sod.domain.SodRule;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessRequestServiceTest {

    // ------------------------------------------------------------------ fixtures

    static final class MemoryStore implements RequestStore {
        final Map<UUID, AccessRequest> requests = new LinkedHashMap<>();
        final Map<UUID, List<ApprovalStep>> steps = new HashMap<>();

        @Override
        public void insert(AccessRequest r, List<ApprovalStep> s) {
            requests.put(r.id(), r);
            steps.put(r.id(), new ArrayList<>(s));
        }

        @Override
        public Optional<AccessRequest> find(UUID id) {
            return Optional.ofNullable(requests.get(id));
        }

        @Override
        public List<ApprovalStep> steps(UUID requestId) {
            return List.copyOf(steps.getOrDefault(requestId, List.of()));
        }

        @Override
        public boolean update(AccessRequest r, long expectedVersion) {
            AccessRequest cur = requests.get(r.id());
            if (cur == null || cur.version() != expectedVersion) {
                return false;
            }
            requests.put(r.id(), new AccessRequest(r.id(), r.requesterId(), r.beneficiaryId(), r.type(), r.roleId(), r.roleCode(), r.scopeType(),
                    r.scopeValue(), r.justification(), r.durationDays(), r.status(), r.statusReason(), r.decisionJson(), r.sodConflictsJson(),
                    r.roleAssignmentId(), r.validUntil(), r.createdAt(), r.updatedAt(), expectedVersion + 1));
            return true;
        }

        @Override
        public void updateStep(ApprovalStep s) {
            List<ApprovalStep> list = steps.get(s.requestId());
            list.replaceAll(x -> x.stepNo() == s.stepNo() ? s : x);
        }

        @Override
        public boolean hasOpenRequest(UUID beneficiaryId, UUID roleId) {
            return requests.values().stream().anyMatch(r -> r.beneficiaryId().equals(beneficiaryId) && r.roleId().equals(roleId) && r.open());
        }

        @Override
        public List<AccessRequest> byRequester(UUID requesterId, int limit) {
            return requests.values().stream().filter(r -> r.requesterId().equals(requesterId)).toList();
        }

        @Override
        public List<AccessRequest> all(String status, int limit) {
            return List.copyOf(requests.values());
        }

        @Override
        public List<AccessRequest> pendingFor(UUID identityId, List<String> roleCodes, int limit) {
            return requests.values().stream().filter(r -> r.status() == AccessRequest.Status.PENDING_APPROVAL).toList();
        }

        @Override
        public Optional<AccessRequest> byAssignment(UUID roleAssignmentId) {
            return requests.values().stream().filter(r -> roleAssignmentId.equals(r.roleAssignmentId())).findFirst();
        }
    }

    static final class Roles implements RoleDirectory {
        final Map<String, RoleView> byCode = new LinkedHashMap<>();
        final Map<UUID, List<String>> held = new HashMap<>();
        final List<String> grants = new ArrayList<>();
        boolean failGrant;

        Roles() {
            for (String code : List.of("HELPDESK", "PLATFORM_ADMINISTRATOR", "SECURITY_ADMINISTRATOR", "IAM_ADMINISTRATOR", "AUDITOR")) {
                byCode.put(code, new RoleView(UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), code, code, null, true, List.of()));
            }
        }

        RoleView role(String code) {
            return byCode.get(code);
        }

        void give(UUID identity, String code) {
            held.computeIfAbsent(identity, k -> new ArrayList<>()).add(code);
        }

        @Override
        public List<RoleView> allRoles() {
            return List.copyOf(byCode.values());
        }

        @Override
        public Optional<RoleView> findRole(UUID roleId) {
            return byCode.values().stream().filter(r -> r.id().equals(roleId)).findFirst();
        }

        @Override
        public List<String> activeRoleCodes(UUID identityId) {
            return List.copyOf(held.getOrDefault(identityId, List.of()));
        }

        @Override
        public List<UUID> activeHolders(String roleCode) {
            return held.entrySet().stream().filter(e -> e.getValue().contains(roleCode)).map(Map.Entry::getKey).toList();
        }

        @Override
        public UUID grantForRequest(UUID identityId, UUID roleId, String scopeType, String scopeValue, Instant validUntil, UUID requestId,
                                    String reason) {
            if (failGrant) {
                throw IamException.alreadyExists("An identical active assignment");
            }
            String code = findRole(roleId).orElseThrow().code();
            give(identityId, code);
            grants.add(code + " " + scopeType + " until " + validUntil);
            return UUID.randomUUID();
        }
    }

    final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
    final UUID ou = UUID.randomUUID();
    final UUID alice = UUID.randomUUID();   // requester
    final UUID manager = UUID.randomUUID(); // alice's manager
    final UUID sec = UUID.randomUUID();     // security administrator
    final UUID contractor = UUID.randomUUID();
    final Map<UUID, IdentitySummary> people = new HashMap<>();
    final Map<UUID, UUID> managers = new HashMap<>();
    final MemoryStore store = new MemoryStore();
    final Roles roles = new Roles();
    final List<AuditEntry> audit = new ArrayList<>();
    final List<Policy> policies = List.of(
            new Policy(UUID.randomUUID(), "P-100", "Standard", null, true, "ALLOW", "ROLE", null, null, List.of("MANAGER"), true, 180, 0),
            new Policy(UUID.randomUUID(), "P-200", "Privileged", null, true, "ALLOW", "ROLE",
                    List.of("PLATFORM_ADMINISTRATOR", "SECURITY_ADMINISTRATOR", "IAM_ADMINISTRATOR"), null,
                    List.of("MANAGER", "ROLE:SECURITY_ADMINISTRATOR"), true, 30, 0),
            new Policy(UUID.randomUUID(), "P-900", "No external admins", null, true, "DENY", "ROLE",
                    List.of("PLATFORM_ADMINISTRATOR"), List.of("CONTRACTOR"), List.of(), false, null, 0));
    final List<SodRule> rules = List.of(new SodRule(UUID.randomUUID(), "SOD-01", "Auditors cannot administer", "AUDITOR",
            "PLATFORM_ADMINISTRATOR", "PREVENTIVE", "HIGH", true));
    final IdentityDirectory identities = new IdentityDirectory() {
        @Override
        public Optional<IdentitySummary> find(UUID identityId) {
            return Optional.ofNullable(people.get(identityId));
        }

        @Override
        public Optional<UUID> managerIdentityOf(UUID identityId) {
            return Optional.ofNullable(managers.get(identityId));
        }
    };
    final AccessRequestService service = new AccessRequestService(store, roles, identities, ctx -> PolicyEvaluator.evaluate(policies, ctx),
            (held, requested) -> rules.stream().flatMap(r -> r.conflict(held, requested).stream()).toList(),
            TestSupport.guard(true), (a, e) -> audit.add(e), TestSupport.DIRECT_TX, clock);

    AccessRequestServiceTest() {
        for (UUID id : List.of(alice, manager, sec)) {
            people.put(id, new IdentitySummary(id, UUID.randomUUID(), "u" + id, "User " + id, null, "EMPLOYEE", "ACTIVE", null, ou, "/IT"));
        }
        people.put(contractor, new IdentitySummary(contractor, UUID.randomUUID(), "c", "Contractor", null, "CONTRACTOR", "ACTIVE", null, ou, "/IT"));
        managers.put(alice, manager);
        roles.give(sec, "SECURITY_ADMINISTRATOR");
    }

    CurrentActor as(UUID id) {
        return TestSupport.actor(id);
    }

    AccessRequestView submit(UUID who, String role, int days) {
        return service.submit(as(who), new AccessRequestService.Submit(roles.role(role).id(), "ORG_UNIT", "project work", days));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void standardRoleNeedsTheManagerAndIsGrantedTimeBound() {
        AccessRequestView r = submit(alice, "HELPDESK", 30);
        assertEquals("PENDING_APPROVAL", r.status());
        assertEquals(1, r.steps().size());
        assertEquals("MANAGER", r.steps().get(0).approverType());
        assertTrue(service.pendingApprovals(as(manager)).stream().anyMatch(x -> x.id().equals(r.id())));
        AccessRequestView done = service.decide(as(manager), r.id(), true, "ok");
        assertEquals("ACTIVE", done.status());
        assertNotNull(done.roleAssignmentId());
        assertEquals(Instant.parse("2026-10-22T10:00:00Z"), done.validUntil());
        assertEquals(List.of("HELPDESK ORG_UNIT until 2026-10-22T10:00:00Z"), roles.grants);
    }

    @Test
    void privilegedRoleNeedsManagerThenSecurityAndDistinctApprovers() {
        AccessRequestView r = submit(alice, "IAM_ADMINISTRATOR", 10);
        assertEquals(List.of("MANAGER", "ROLE"), r.steps().stream().map(AccessRequestView.Step::approverType).toList());
        assertThrows(IamException.class, () -> service.decide(as(sec), r.id(), true, null), "security cannot decide the manager step");
        service.decide(as(manager), r.id(), true, null);
        assertEquals("PENDING_APPROVAL", service.get(as(alice), r.id()).status());
        roles.give(manager, "SECURITY_ADMINISTRATOR");
        assertEquals("ALREADY_DECIDED", assertThrows(IamException.class, () -> service.decide(as(manager), r.id(), true, null)).details().get(0).code());
        assertEquals("ACTIVE", service.decide(as(sec), r.id(), true, null).status());
    }

    @Test
    void requesterCannotApproveAndRejectionNeedsAReason() {
        AccessRequestView r = submit(alice, "HELPDESK", 5);
        assertEquals("SELF_APPROVAL", assertThrows(IamException.class, () -> service.decide(as(alice), r.id(), true, null)).details().get(0).code());
        assertThrows(IamException.class, () -> service.decide(as(manager), r.id(), false, " "));
        AccessRequestView rejected = service.decide(as(manager), r.id(), false, "not needed");
        assertEquals("REJECTED", rejected.status());
        assertTrue(roles.grants.isEmpty());
    }

    @Test
    void policyDenyAndDurationLimitRejectAtSubmission() {
        assertEquals("REJECTED", submit(contractor, "PLATFORM_ADMINISTRATOR", 5).status());
        AccessRequestView tooLong = submit(alice, "PLATFORM_ADMINISTRATOR", 90);
        assertEquals("REJECTED", tooLong.status());
        assertTrue(tooLong.statusReason().contains("maximum of 30 days"), tooLong.statusReason());
    }

    @Test
    void preventiveSodConflictRejects() {
        roles.give(alice, "AUDITOR");
        AccessRequestView r = submit(alice, "PLATFORM_ADMINISTRATOR", 5);
        assertEquals("REJECTED", r.status());
        assertTrue(r.statusReason().contains("SOD-01"));
        assertEquals(1, r.sodConflicts().size());
    }

    @Test
    void noManagerRoutesToIamAdministratorsAndDuplicatesAreRefused() {
        managers.remove(alice);
        AccessRequestView r = submit(alice, "HELPDESK", 5);
        assertEquals("IAM_ADMINISTRATOR", r.steps().get(0).approverRole());
        assertNotNull(r.steps().get(0).note());
        assertThrows(IamException.class, () -> submit(alice, "HELPDESK", 5), "one open request per role");
        assertEquals("CANCELLED", service.cancel(as(alice), r.id()).status());
    }

    @Test
    void failedGrantIsRecordedAndExpiryIsTracked() {
        roles.failGrant = true;
        AccessRequestView r = submit(alice, "HELPDESK", 5);
        assertEquals("FAILED", service.decide(as(manager), r.id(), true, null).status());
        roles.failGrant = false;
        roles.held.clear();
        roles.give(sec, "SECURITY_ADMINISTRATOR");
        AccessRequestView ok = submit(alice, "IAM_ADMINISTRATOR", 5);
        service.decide(as(manager), ok.id(), true, null);
        AccessRequestView active = service.decide(as(sec), ok.id(), true, null);
        service.onAssignmentChanged(active.roleAssignmentId(), "EXPIRED");
        assertEquals("EXPIRED", service.get(as(alice), ok.id()).status());
        assertFalse(audit.isEmpty());
    }

    @Test
    void requestableRolesShowWhatThePolicyWillRequire() {
        var list = service.requestableRoles(as(alice));
        var admin = list.stream().filter(r -> r.code().equals("PLATFORM_ADMINISTRATOR")).findFirst().orElseThrow();
        assertEquals(List.of("MANAGER", "ROLE:SECURITY_ADMINISTRATOR"), admin.approvals());
        assertEquals(Integer.valueOf(30), admin.maxDurationDays());
        assertTrue(service.requestableRoles(as(contractor)).stream().filter(r -> r.code().equals("PLATFORM_ADMINISTRATOR")).noneMatch(r -> r.allowed()));
    }
}
