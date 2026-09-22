package com.enterprise.iam.core.policy.api;

/** Facts a policy decision is made on (ADR-0013). Values are snapshots taken at submission and re-checked at approval. */
public record RequestContext(String requestType, String roleCode, String identityType, int requestedDurationDays) {
}
