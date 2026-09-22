package com.enterprise.iam.core.request.application;

import com.enterprise.iam.core.account.api.CredentialCheckouts;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.authorization.api.RoleDirectory;
import com.enterprise.iam.core.authorization.api.RoleView;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.policy.api.PolicyDecision;
import com.enterprise.iam.core.policy.api.PolicyDecisionPoint;
import com.enterprise.iam.core.policy.api.RequestContext;
import com.enterprise.iam.core.request.api.AccessRequestChanged;
import com.enterprise.iam.core.request.api.AccessRequestView;
import com.enterprise.iam.core.request.api.RequestableRole;
import com.enterprise.iam.core.request.domain.AccessRequest;
import com.enterprise.iam.core.request.domain.ApprovalStep;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.sod.api.SodChecker;
import com.enterprise.iam.core.sod.api.SodConflict;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import com.enterprise.iam.kernel.Json;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Access requests for time-bound roles (spec §20–§21, DOMAIN-MODEL §8): policy decision (deny-overrides) and SoD check at
 * submission, sequential approvals (manager, then role holders), re-evaluation before fulfilment, automatic grant with an
 * expiry, and status tracking until the grant expires or is revoked.
 *
 * <p>Invariants: requester and beneficiary never approve; one identity decides at most one step of a request; an open
 * request per beneficiary and role; every decision records the approver's authentication context.
 */
public class AccessRequestService {

    public record Submit(UUID roleId, String scopeType, String justification, int durationDays) {
    }

    public record SubmitCredential(UUID accountId, String justification, int durationHours) {
    }

    static final String FALLBACK_MANAGER_ROLE = "IAM_ADMINISTRATOR";
    static final String FALLBACK_ROLE = "PLATFORM_ADMINISTRATOR";
    private static final int LIST_LIMIT = 200;

    private final RequestStore store;
    private final RoleDirectory roles;
    private final IdentityDirectory identities;
    private final PolicyDecisionPoint pdp;
    private final SodChecker sod;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final DomainEventPublisher events;
    private final TransactionRunner tx;
    private final Clock clock;
    private final CredentialCheckouts checkouts;

    public AccessRequestService(RequestStore store, RoleDirectory roles, IdentityDirectory identities, PolicyDecisionPoint pdp, SodChecker sod,
                                AccessGuard guard, AuditRecorder audit, DomainEventPublisher events, TransactionRunner tx, Clock clock,
                                CredentialCheckouts checkouts) {
        this.checkouts = checkouts;
        this.store = store;
        this.roles = roles;
        this.identities = identities;
        this.pdp = pdp;
        this.sod = sod;
        this.guard = guard;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ catalog

    public List<RequestableRole> requestableRoles(CurrentActor actor) {
        IdentitySummary me = activeIdentity(actor.identityId());
        List<String> held = roles.activeRoleCodes(me.id());
        return roles.allRoles().stream().map(r -> {
            PolicyDecision d = pdp.evaluate(new RequestContext("ROLE", r.code(), me.type(), 1));
            return new RequestableRole(r.id(), r.code(), r.name(), r.description(), held.contains(r.code()), d.allowed(), d.approvals(),
                    d.requireJustification(), d.maxDurationDays(), d.explanation());
        }).toList();
    }

    // ------------------------------------------------------------------ submit

    public AccessRequestView submit(CurrentActor actor, Submit cmd) {
        IdentitySummary beneficiary = activeIdentity(actor.identityId());
        RoleView role = roles.findRole(cmd.roleId()).orElseThrow(() -> IamException.validation("roleId", "NOT_FOUND", "role does not exist"));
        if (cmd.durationDays() < 1 || cmd.durationDays() > 3650) {
            throw IamException.validation("durationDays", "OUT_OF_RANGE", "duration must be between 1 and 3650 days");
        }
        String scopeType = "GLOBAL".equals(cmd.scopeType()) ? "GLOBAL" : "ORG_UNIT";
        String scopeValue;
        if ("GLOBAL".equals(scopeType)) {
            scopeValue = "*";
        } else if (beneficiary.orgUnitId() != null) {
            scopeValue = beneficiary.orgUnitId().toString();
        } else {
            throw IamException.validation("scopeType", "NO_ORG_UNIT", "you are not in an organization unit; request the role platform-wide");
        }
        List<String> held = roles.activeRoleCodes(beneficiary.id());
        if (held.contains(role.code())) {
            throw IamException.validation("roleId", "ALREADY_HELD", "you already hold this role");
        }
        PolicyDecision decision = pdp.evaluate(new RequestContext("ROLE", role.code(), beneficiary.type(), cmd.durationDays()));
        if (decision.allowed() && decision.requireJustification() && (cmd.justification() == null || cmd.justification().isBlank())) {
            throw IamException.validation("justification", "REQUIRED", "a justification is required for this role");
        }
        List<SodConflict> conflicts = sod.check(held, role.code());
        Instant now = clock.instant();
        UUID id = Ids.newId(clock);
        AccessRequest.Status status;
        String reason = null;
        List<ApprovalStep> steps = new ArrayList<>();
        if (!decision.allowed()) {
            status = AccessRequest.Status.REJECTED;
            reason = "policy: " + decision.explanation();
        } else if (conflicts.stream().anyMatch(SodConflict::blocking)) {
            SodConflict c = conflicts.stream().filter(SodConflict::blocking).findFirst().orElseThrow();
            status = AccessRequest.Status.REJECTED;
            reason = "separation of duties: " + c.ruleCode() + " " + c.ruleName() + " (you hold " + c.heldRole() + ")";
        } else {
            steps = buildSteps(id, decision.approvals(), beneficiary.id(), actor.identityId());
            status = steps.isEmpty() ? AccessRequest.Status.APPROVED : AccessRequest.Status.PENDING_APPROVAL;
        }
        AccessRequest req = new AccessRequest(id, actor.identityId(), beneficiary.id(), "ROLE", role.id(), role.code(), scopeType, scopeValue,
                blankToNull(cmd.justification()), cmd.durationDays(), status, reason, decisionJson(decision), conflictsJson(conflicts), null, null,
                now, now, 0, null, null);
        List<ApprovalStep> finalSteps = steps;
        tx.run(() -> {
            if (status != AccessRequest.Status.REJECTED && store.hasOpenRequest(beneficiary.id(), role.id())) {
                throw IamException.alreadyExists("An open request for this role");
            }
            store.insert(req, finalSteps);
            audit.record(actor, AuditEntry.success("access-request.submitted", "access-request", id, Map.of("role", role.code(),
                    "status", status.name(), "policies", String.join(",", decision.matchedPolicies()), "scope", scopeType)));
            if (status == AccessRequest.Status.PENDING_APPROVAL) {
                publishPending(req, finalSteps.get(0));
            }
        });
        if (status == AccessRequest.Status.APPROVED) {
            fulfil(id);
        }
        return get(actor, id);
    }

    /** Vaulted privileged accounts the caller can request a checkout of, with the policy outcome. */
    public List<Map<String, Object>> requestableCredentials(CurrentActor actor) {
        IdentitySummary me = activeIdentity(actor.identityId());
        PolicyDecision d = pdp.evaluate(new RequestContext("CREDENTIAL", null, me.type(), 1));
        return checkouts.checkoutTargets().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", t.accountId());
            m.put("accountName", t.accountName());
            m.put("targetId", t.targetId());
            m.put("targetName", t.targetName());
            m.put("providerType", t.providerType());
            m.put("available", t.available());
            m.put("unavailableReason", t.unavailableReason());
            m.put("allowed", d.allowed());
            m.put("approvals", d.approvals());
            m.put("requireJustification", d.requireJustification());
            m.put("maxDurationDays", d.maxDurationDays());
            m.put("explanation", d.explanation());
            return m;
        }).toList();
    }

    /** Requests a time-bound checkout of a vaulted privileged password (ADR-0021). */
    public AccessRequestView submitCredential(CurrentActor actor, SubmitCredential cmd) {
        IdentitySummary beneficiary = activeIdentity(actor.identityId());
        CredentialCheckouts.CheckoutTarget target = checkouts.checkoutTarget(cmd.accountId())
                .orElseThrow(() -> IamException.validation("accountId", "NOT_VAULTED", "the platform does not manage this account's password"));
        if (cmd.durationHours() < 1 || cmd.durationHours() > 72) {
            throw IamException.validation("durationHours", "OUT_OF_RANGE", "a checkout lasts between 1 and 72 hours");
        }
        int days = (cmd.durationHours() + 23) / 24;
        PolicyDecision decision = pdp.evaluate(new RequestContext("CREDENTIAL", null, beneficiary.type(), days));
        if (decision.allowed() && decision.requireJustification() && (cmd.justification() == null || cmd.justification().isBlank())) {
            throw IamException.validation("justification", "REQUIRED", "a justification is required to check out a privileged password");
        }
        Instant now = clock.instant();
        UUID id = Ids.newId(clock);
        AccessRequest.Status status;
        String reason = null;
        List<ApprovalStep> steps = new ArrayList<>();
        if (!decision.allowed()) {
            status = AccessRequest.Status.REJECTED;
            reason = "policy: " + decision.explanation();
        } else {
            steps = buildSteps(id, decision.approvals(), beneficiary.id(), actor.identityId());
            status = steps.isEmpty() ? AccessRequest.Status.APPROVED : AccessRequest.Status.PENDING_APPROVAL;
        }
        AccessRequest req = new AccessRequest(id, actor.identityId(), beneficiary.id(), "CREDENTIAL", null, target.label(), "GLOBAL", "*",
                blankToNull(cmd.justification()), days, status, reason, decisionJson(decision), "[]", null, null, now, now, 0, target.accountId(),
                cmd.durationHours());
        List<ApprovalStep> finalSteps = steps;
        tx.run(() -> {
            if (status != AccessRequest.Status.REJECTED && store.hasOpenCredentialRequest(beneficiary.id(), target.accountId())) {
                throw IamException.alreadyExists("An open request for this account");
            }
            store.insert(req, finalSteps);
            audit.record(actor, AuditEntry.success("access-request.submitted", "access-request", id, Map.of("type", "CREDENTIAL",
                    "account", target.accountId().toString(), "status", status.name(), "policies", String.join(",", decision.matchedPolicies()),
                    "hours", String.valueOf(cmd.durationHours()))));
            if (status == AccessRequest.Status.PENDING_APPROVAL) {
                publishPending(req, finalSteps.get(0));
            }
        });
        if (status == AccessRequest.Status.APPROVED) {
            fulfil(id);
        }
        return get(actor, id);
    }

    private List<ApprovalStep> buildSteps(UUID requestId, List<String> approvals, UUID beneficiaryId, UUID requesterId) {
        List<ApprovalStep> steps = new ArrayList<>();
        int no = 1;
        for (String a : approvals) {
            String status = no == 1 ? "PENDING" : "WAITING";
            if ("MANAGER".equals(a)) {
                Optional<UUID> manager = identities.managerIdentityOf(beneficiaryId).filter(m -> !m.equals(requesterId));
                steps.add(manager.map(m -> new ApprovalStep(requestId, 0, "MANAGER", null, m, status, null, null, null, null, null))
                        .orElseGet(() -> new ApprovalStep(requestId, 0, "ROLE", FALLBACK_MANAGER_ROLE, null, status, null, null, null, null,
                                "no active manager recorded: routed to " + FALLBACK_MANAGER_ROLE)));
            } else if (a.startsWith("ROLE:")) {
                String code = a.substring(5);
                boolean someoneElse = roles.activeHolders(code).stream().anyMatch(h -> !h.equals(requesterId));
                steps.add(someoneElse ? new ApprovalStep(requestId, 0, "ROLE", code, null, status, null, null, null, null, null)
                        : new ApprovalStep(requestId, 0, "ROLE", FALLBACK_ROLE, null, status, null, null, null, null,
                                "no other holder of " + code + ": routed to " + FALLBACK_ROLE));
            } else {
                continue;
            }
            ApprovalStep s = steps.remove(steps.size() - 1);
            steps.add(new ApprovalStep(s.requestId(), no++, s.approverType(), s.approverRole(), s.approverIdentityId(), s.status(), null, null,
                    null, null, s.note()));
        }
        return steps;
    }

    // ------------------------------------------------------------------ decisions

    public AccessRequestView decide(CurrentActor actor, UUID requestId, boolean approve, String comment) {
        boolean completed = tx.inTransaction(() -> {
            AccessRequest req = store.find(requestId).orElseThrow(() -> IamException.notFound("Access request"));
            if (req.status() != AccessRequest.Status.PENDING_APPROVAL) {
                throw IamException.invalidTransition("AccessRequest", req.status(), approve ? "APPROVE" : "REJECT");
            }
            List<ApprovalStep> steps = store.steps(requestId);
            ApprovalStep current = steps.stream().filter(s -> "PENDING".equals(s.status())).findFirst()
                    .orElseThrow(() -> IamException.invalidTransition("AccessRequest", "NO_PENDING_STEP", "DECIDE"));
            requireEligible(actor, req, steps, current);
            if (!approve && (comment == null || comment.isBlank())) {
                throw IamException.validation("comment", "REQUIRED", "a reason is required to reject");
            }
            Instant now = clock.instant();
            store.updateStep(current.decide(approve, actor.identityId(), now, blankToNull(comment), actor.acr()));
            audit.record(actor, AuditEntry.success(approve ? "access-request.approved-step" : "access-request.rejected", "access-request", requestId,
                    Map.of("step", String.valueOf(current.stepNo()), "role", req.roleCode(), "acr", String.valueOf(actor.acr()))));
            if (!approve) {
                String why = "rejected at step " + current.stepNo() + " (" + current.describe() + "): " + comment.strip();
                save(req.withStatus(AccessRequest.Status.REJECTED, why, now), req);
                publishOutcome(req, "REJECTED", why);
                return false;
            }
            Optional<ApprovalStep> next = steps.stream().filter(s -> s.stepNo() > current.stepNo() && "WAITING".equals(s.status())).findFirst();
            if (next.isPresent()) {
                store.updateStep(next.get().withStatus("PENDING"));
                save(req.withStatus(AccessRequest.Status.PENDING_APPROVAL, null, now), req);
                publishPending(req, next.get());
                return false;
            }
            // All approvals collected: re-evaluate policy and SoD before granting (decisions may have changed meanwhile).
            IdentitySummary beneficiary = identities.find(req.beneficiaryId()).orElseThrow(() -> IamException.notFound("Identity"));
            PolicyDecision d = pdp.evaluate(context(req, beneficiary.type()));
            List<SodConflict> conflicts = req.credential() ? List.of() : sod.check(roles.activeRoleCodes(req.beneficiaryId()), req.roleCode());
            if (!d.allowed() || conflicts.stream().anyMatch(SodConflict::blocking) || !"ACTIVE".equals(beneficiary.state())) {
                save(req.withStatus(AccessRequest.Status.REJECTED, "re-evaluation before grant failed: "
                        + (!d.allowed() ? d.explanation() : !"ACTIVE".equals(beneficiary.state()) ? "beneficiary is " + beneficiary.state()
                        : "separation-of-duties conflict"), now), req);
                return false;
            }
            save(req.withStatus(AccessRequest.Status.APPROVED, null, now), req);
            return true;
        });
        if (completed) {
            fulfil(requestId);
        }
        return get(actor, requestId);
    }

    private void requireEligible(CurrentActor actor, AccessRequest req, List<ApprovalStep> steps, ApprovalStep current) {
        UUID me = actor.identityId();
        if (me.equals(req.requesterId()) || me.equals(req.beneficiaryId())) {
            throw IamException.validation("requestId", "SELF_APPROVAL", "requesters and beneficiaries cannot approve their own request");
        }
        if (steps.stream().anyMatch(s -> me.equals(s.decidedBy()))) {
            throw IamException.validation("requestId", "ALREADY_DECIDED", "one person can approve only one step of a request");
        }
        boolean eligible = "MANAGER".equals(current.approverType()) ? me.equals(current.approverIdentityId())
                : roles.activeRoleCodes(me).contains(current.approverRole());
        if (!eligible) {
            throw IamException.accessDenied();
        }
    }

    public AccessRequestView cancel(CurrentActor actor, UUID requestId) {
        tx.run(() -> {
            AccessRequest req = store.find(requestId).orElseThrow(() -> IamException.notFound("Access request"));
            if (!actor.identityId().equals(req.requesterId())) {
                throw IamException.accessDenied();
            }
            if (req.status() != AccessRequest.Status.PENDING_APPROVAL) {
                throw IamException.invalidTransition("AccessRequest", req.status(), "CANCEL");
            }
            save(req.withStatus(AccessRequest.Status.CANCELLED, "cancelled by the requester", clock.instant()), req);
            audit.record(actor, AuditEntry.success("access-request.cancelled", "access-request", requestId, Map.of("role", req.roleCode())));
        });
        return get(actor, requestId);
    }

    // ------------------------------------------------------------------ fulfilment and lifecycle

    /** Grants the role (own transaction) and records the outcome; never leaves an APPROVED request silently. */
    void fulfil(UUID requestId) {
        AccessRequest req = tx.readOnly(() -> store.find(requestId)).orElseThrow();
        if (req.status() != AccessRequest.Status.APPROVED) {
            return;
        }
        CurrentActor system = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);
        if (req.credential()) {
            fulfilCredential(req, system);
            return;
        }
        PolicyDecision d = pdp.evaluate(context(req, identities.find(req.beneficiaryId()).map(IdentitySummary::type).orElse("UNKNOWN")));
        int days = d.maxDurationDays() == null ? req.durationDays() : Math.min(req.durationDays(), d.maxDurationDays());
        Instant until = clock.instant().plus(Duration.ofDays(days));
        try {
            UUID assignment = roles.grantForRequest(req.beneficiaryId(), req.roleId(), req.scopeType(), req.scopeValue(), until, req.id(),
                    "access request " + req.id());
            tx.run(() -> {
                AccessRequest cur = store.find(requestId).orElseThrow();
                save(cur.fulfilled(assignment, until, clock.instant()), cur);
                publishOutcome(cur, "ACTIVE", "granted until " + until);
                audit.record(system, AuditEntry.success("access-request.fulfilled", "access-request", requestId,
                        Map.of("role", req.roleCode(), "assignment", assignment.toString(), "validUntil", until.toString())));
            });
        } catch (IamException e) {
            tx.run(() -> {
                AccessRequest cur = store.find(requestId).orElseThrow();
                save(cur.withStatus(AccessRequest.Status.FAILED, "grant failed: " + e.getMessage(), clock.instant()), cur);
                publishOutcome(cur, "FAILED", "grant failed: " + e.getMessage());
                audit.record(system, AuditEntry.success("access-request.failed", "access-request", requestId, Map.of("reason", String.valueOf(e.getMessage()))));
            });
        }
    }

    private void fulfilCredential(AccessRequest req, CurrentActor system) {
        Duration duration = Duration.ofHours(req.durationHours() == null ? 1 : req.durationHours());
        Instant until = clock.instant().plus(duration);
        try {
            UUID checkout = checkouts.grant(req.accountId(), req.beneficiaryId(), req.id(), duration,
                    req.justification() == null ? "access request " + req.id() : req.justification());
            tx.run(() -> {
                AccessRequest cur = store.find(req.id()).orElseThrow();
                save(cur.fulfilled(checkout, until, clock.instant()), cur);
                publishOutcome(cur, "ACTIVE", "checked out until " + until);
                audit.record(system, AuditEntry.success("access-request.fulfilled", "access-request", req.id(),
                        Map.of("type", "CREDENTIAL", "checkout", checkout.toString(), "validUntil", until.toString())));
            });
        } catch (IamException e) {
            tx.run(() -> {
                AccessRequest cur = store.find(req.id()).orElseThrow();
                save(cur.withStatus(AccessRequest.Status.FAILED, "checkout failed: " + e.getMessage(), clock.instant()), cur);
                publishOutcome(cur, "FAILED", "checkout failed: " + e.getMessage());
            });
        }
    }

    private static RequestContext context(AccessRequest req, String identityType) {
        return req.credential() ? new RequestContext("CREDENTIAL", null, identityType, req.durationDays())
                : new RequestContext("ROLE", req.roleCode(), identityType, req.durationDays());
    }

    /**
     * Keeps the request in line with its fulfilment: a role assignment expired or revoked, or a credential checkout checked
     * in, expired or revoked.
     */
    public void onAssignmentChanged(UUID assignmentId, String change) {
        if ("CHECKED_IN".equals(change)) {
            change = "EXPIRED";
        }
        if (!"EXPIRED".equals(change) && !"REVOKED".equals(change)) {
            return;
        }
        String outcome = change;
        tx.run(() -> store.byAssignment(assignmentId).filter(r -> r.status() == AccessRequest.Status.ACTIVE).ifPresent(r ->
                save(r.withStatus("EXPIRED".equals(outcome) ? AccessRequest.Status.EXPIRED : AccessRequest.Status.REVOKED, null, clock.instant()), r)));
    }

    // ------------------------------------------------------------------ queries

    public List<AccessRequestView> mine(CurrentActor actor) {
        return tx.readOnly(() -> store.byRequester(actor.identityId(), LIST_LIMIT)).stream().map(r -> view(actor, r)).toList();
    }

    public List<AccessRequestView> all(CurrentActor actor, String status) {
        guard.require(actor, Permissions.REQUEST_READ, ResourceScope.PLATFORM, false);
        return tx.readOnly(() -> store.all(status, LIST_LIMIT)).stream().map(r -> view(actor, r)).toList();
    }

    public List<AccessRequestView> pendingApprovals(CurrentActor actor) {
        List<String> myRoles = roles.activeRoleCodes(actor.identityId());
        return tx.readOnly(() -> store.pendingFor(actor.identityId(), myRoles, LIST_LIMIT)).stream()
                .map(r -> view(actor, r)).filter(AccessRequestView::canDecide).toList();
    }

    public AccessRequestView get(CurrentActor actor, UUID id) {
        AccessRequest r = tx.readOnly(() -> store.find(id)).orElseThrow(() -> IamException.notFound("Access request"));
        AccessRequestView v = view(actor, r);
        boolean involved = actor.identityId().equals(r.requesterId()) || actor.identityId().equals(r.beneficiaryId()) || v.canDecide()
                || v.steps().stream().anyMatch(s -> actor.identityId().equals(s.decidedBy()));
        if (!involved) {
            guard.require(actor, Permissions.REQUEST_READ, ResourceScope.PLATFORM, true);
        }
        return v;
    }

    // ------------------------------------------------------------------ helpers

    private void publishPending(AccessRequest req, ApprovalStep step) {
        List<UUID> approvers = "MANAGER".equals(step.approverType()) ? List.of(step.approverIdentityId())
                : roles.activeHolders(step.approverRole()).stream().filter(h -> !h.equals(req.requesterId()) && !h.equals(req.beneficiaryId())).toList();
        events.publish(new AccessRequestChanged(req.id(), req.requesterId(), name(req.requesterId()), req.roleCode(), "PENDING_APPROVAL", null,
                approvers));
    }

    private void publishOutcome(AccessRequest req, String status, String reason) {
        events.publish(new AccessRequestChanged(req.id(), req.requesterId(), name(req.requesterId()), req.roleCode(), status, reason,
                List.of(req.requesterId())));
    }

    private void save(AccessRequest next, AccessRequest current) {
        if (!store.update(next, current.version())) {
            throw IamException.concurrentModification("Access request");
        }
    }

    private IdentitySummary activeIdentity(UUID id) {
        IdentitySummary s = identities.find(id).orElseThrow(() -> IamException.notFound("Identity"));
        if (!"ACTIVE".equals(s.state())) {
            throw IamException.validation("identity", "INACTIVE", "only ACTIVE identities can request access");
        }
        return s;
    }

    private AccessRequestView view(CurrentActor actor, AccessRequest r) {
        List<ApprovalStep> steps = tx.readOnly(() -> store.steps(r.id()));
        UUID me = actor.identityId();
        boolean canDecide = false;
        if (r.status() == AccessRequest.Status.PENDING_APPROVAL && !me.equals(r.requesterId()) && !me.equals(r.beneficiaryId())
                && steps.stream().noneMatch(s -> me.equals(s.decidedBy()))) {
            Optional<ApprovalStep> cur = steps.stream().filter(s -> "PENDING".equals(s.status())).findFirst();
            canDecide = cur.map(s -> "MANAGER".equals(s.approverType()) ? me.equals(s.approverIdentityId())
                    : roles.activeRoleCodes(me).contains(s.approverRole())).orElse(false);
        }
        Map<String, Object> decision = Json.parseObject(r.decisionJson());
        List<AccessRequestView.Conflict> conflicts = new ArrayList<>();
        if (Json.parse(r.sodConflictsJson()) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    conflicts.add(new AccessRequestView.Conflict(str(m.get("ruleCode")), str(m.get("ruleName")), str(m.get("heldRole")),
                            str(m.get("mode")), str(m.get("severity"))));
                }
            }
        }
        List<AccessRequestView.Step> stepViews = steps.stream().map(s -> new AccessRequestView.Step(s.stepNo(), s.approverType(), s.approverRole(),
                s.approverIdentityId(), name(s.approverIdentityId()), s.status(), s.decidedBy(), name(s.decidedBy()), s.decidedAt(), s.comment(),
                s.note())).toList();
        List<String> matched = decision.get("matchedPolicies") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return new AccessRequestView(r.id(), r.requesterId(), name(r.requesterId()), r.beneficiaryId(), name(r.beneficiaryId()), r.roleId(),
                r.roleCode(), r.scopeType(), r.scopeValue(), r.justification(), r.durationDays(), r.status().name(), r.statusReason(),
                str(decision.get("explanation")), matched, conflicts, stepViews, r.roleAssignmentId(), r.validUntil(), r.createdAt(), r.updatedAt(),
                canDecide, me.equals(r.requesterId()) && r.status() == AccessRequest.Status.PENDING_APPROVAL, r.type(), r.accountId(),
                r.durationHours());
    }

    private String name(UUID id) {
        return id == null ? null : identities.find(id).map(IdentitySummary::displayName).orElse(null);
    }

    static String decisionJson(PolicyDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("effect", d.effect());
        m.put("approvals", d.approvals());
        m.put("requireJustification", d.requireJustification());
        m.put("maxDurationDays", d.maxDurationDays());
        m.put("matchedPolicies", d.matchedPolicies());
        m.put("explanation", d.explanation());
        return Json.write(m);
    }

    static String conflictsJson(List<SodConflict> conflicts) {
        return Json.write(conflicts.stream().map(c -> Map.of("ruleCode", c.ruleCode(), "ruleName", c.ruleName(), "heldRole", c.heldRole(),
                "mode", c.mode(), "severity", c.severity())).toList());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
