package com.enterprise.iam.core.request.domain;

import java.time.Instant;
import java.util.UUID;

/** One sequential approval step: the beneficiary's manager or any holder of a role. Decisions are immutable. */
public record ApprovalStep(UUID requestId, int stepNo, String approverType, String approverRole, UUID approverIdentityId, String status,
                           UUID decidedBy, Instant decidedAt, String comment, String authContext, String note) {

    public ApprovalStep decide(boolean approve, UUID actor, Instant now, String commentText, String acr) {
        return new ApprovalStep(requestId, stepNo, approverType, approverRole, approverIdentityId, approve ? "APPROVED" : "REJECTED",
                actor, now, commentText, acr, note);
    }

    public ApprovalStep withStatus(String next) {
        return new ApprovalStep(requestId, stepNo, approverType, approverRole, approverIdentityId, next, decidedBy, decidedAt, comment,
                authContext, note);
    }

    public String describe() {
        return "MANAGER".equals(approverType) ? "manager" : "role " + approverRole;
    }
}
