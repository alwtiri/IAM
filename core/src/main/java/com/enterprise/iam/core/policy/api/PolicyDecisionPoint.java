package com.enterprise.iam.core.policy.api;

/** Central, synchronous, fail-closed decision point (spec §23, ADR-0013). */
public interface PolicyDecisionPoint {

    /** Never throws: any evaluation problem yields DENY. */
    PolicyDecision evaluate(RequestContext context);
}
