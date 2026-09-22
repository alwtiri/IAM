package com.enterprise.iam.core.policy.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.policy.api.PolicyDecision;
import com.enterprise.iam.core.policy.api.PolicyDecisionPoint;
import com.enterprise.iam.core.policy.api.PolicyView;
import com.enterprise.iam.core.policy.api.RequestContext;
import com.enterprise.iam.core.policy.domain.Policy;
import com.enterprise.iam.core.policy.domain.PolicyEvaluator;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Policy administration and the decision point (fail closed: any error → DENY). */
public class PolicyService implements PolicyDecisionPoint {

    private final PolicyStore store;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;

    public PolicyService(PolicyStore store, AccessGuard guard, AuditRecorder audit, TransactionRunner tx) {
        this.store = store;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
    }

    @Override
    public PolicyDecision evaluate(RequestContext context) {
        try {
            return PolicyEvaluator.evaluate(tx.readOnly(store::all), context);
        } catch (RuntimeException e) {
            return new PolicyDecision("DENY", List.of(), false, null, List.of(), "policy evaluation failed (fail closed)");
        }
    }

    public List<PolicyView> list(CurrentActor actor) {
        guard.require(actor, Permissions.POLICY_READ, ResourceScope.PLATFORM, false);
        return tx.readOnly(store::all).stream().map(PolicyService::view).toList();
    }

    public PolicyView setEnabled(CurrentActor actor, UUID id, boolean enabled) {
        return tx.inTransaction(() -> {
            guard.require(actor, Permissions.POLICY_WRITE, ResourceScope.PLATFORM, false);
            Policy p = store.find(id).orElseThrow(() -> IamException.notFound("Policy"));
            if (p.enabled() != enabled && !store.setEnabled(id, enabled, p.version())) {
                throw IamException.concurrentModification("Policy");
            }
            audit.record(actor, AuditEntry.success(enabled ? "policy.enabled" : "policy.disabled", "policy", id, Map.of("code", p.code())));
            return view(store.find(id).orElseThrow());
        });
    }

    static PolicyView view(Policy p) {
        return new PolicyView(p.id(), p.code(), p.name(), p.description(), p.enabled(), p.effect(), p.requestType(), p.roleCodes(),
                p.identityTypes(), p.approvals(), p.requireJustification(), p.maxDurationDays(), p.version());
    }
}
