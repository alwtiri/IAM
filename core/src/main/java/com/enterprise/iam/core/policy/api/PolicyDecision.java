package com.enterprise.iam.core.policy.api;

import java.util.List;

/**
 * Deny-overrides decision with the union of ALLOW obligations. {@code approvals} are ordered steps ({@code MANAGER},
 * {@code ROLE:<code>}); {@code maxDurationDays} is the strictest limit of all matching ALLOW policies.
 */
public record PolicyDecision(String effect, List<String> approvals, boolean requireJustification, Integer maxDurationDays,
                             List<String> matchedPolicies, String explanation) {

    public PolicyDecision {
        approvals = List.copyOf(approvals);
        matchedPolicies = List.copyOf(matchedPolicies);
    }

    public boolean allowed() {
        return "ALLOW".equals(effect);
    }
}
