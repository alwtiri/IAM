package com.enterprise.iam.core.policy.domain;

import com.enterprise.iam.core.policy.api.PolicyDecision;
import com.enterprise.iam.core.policy.api.RequestContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure evaluator (ADR-0013): deny-overrides; no matching ALLOW → DENY; obligations of matching ALLOW policies are
 * unioned (approval steps keep first-seen order, MANAGER first), the shortest maximum duration wins.
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {
    }

    public static PolicyDecision evaluate(List<Policy> policies, RequestContext ctx) {
        List<Policy> matching = policies.stream().filter(p -> p.matches(ctx.requestType(), ctx.roleCode(), ctx.identityType()))
                .sorted((a, b) -> a.code().compareTo(b.code())).toList();
        List<String> matched = matching.stream().map(Policy::code).toList();
        List<Policy> denies = matching.stream().filter(p -> "DENY".equals(p.effect())).toList();
        if (!denies.isEmpty()) {
            return new PolicyDecision("DENY", List.of(), false, null, matched, "denied by " + denies.get(0).code() + ": " + denies.get(0).name());
        }
        List<Policy> allows = matching.stream().filter(p -> "ALLOW".equals(p.effect())).toList();
        if (allows.isEmpty()) {
            return new PolicyDecision("DENY", List.of(), false, null, matched, "no policy allows this request");
        }
        Set<String> steps = new LinkedHashSet<>();
        allows.stream().flatMap(p -> p.approvals().stream()).filter("MANAGER"::equals).findFirst().ifPresent(steps::add);
        allows.forEach(p -> steps.addAll(p.approvals()));
        boolean justification = allows.stream().anyMatch(Policy::requireJustification);
        Integer max = allows.stream().map(Policy::maxDurationDays).filter(d -> d != null).min(Integer::compare).orElse(null);
        if (max != null && ctx.requestedDurationDays() > max) {
            return new PolicyDecision("DENY", new ArrayList<>(steps), justification, max, matched,
                    "requested " + ctx.requestedDurationDays() + " days exceeds the maximum of " + max + " days");
        }
        return new PolicyDecision("ALLOW", new ArrayList<>(steps), justification, max, matched,
                "allowed by " + String.join(", ", allows.stream().map(Policy::code).toList()));
    }
}
