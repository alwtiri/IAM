package com.enterprise.iam.core.request.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccessRequestView(UUID id, UUID requesterId, String requesterName, UUID beneficiaryId, String beneficiaryName, UUID roleId,
                                String roleCode, String scopeType, String scopeValue, String justification, int durationDays, String status,
                                String statusReason, String policyExplanation, List<String> matchedPolicies, List<Conflict> sodConflicts,
                                List<Step> steps, UUID roleAssignmentId, Instant validUntil, Instant createdAt, Instant updatedAt,
                                boolean canDecide, boolean canCancel, String type, UUID accountId, Integer durationHours) {

    public record Step(int stepNo, String approverType, String approverRole, UUID approverIdentityId, String approverName, String status,
                       UUID decidedBy, String decidedByName, Instant decidedAt, String comment, String note) {
    }

    public record Conflict(String ruleCode, String ruleName, String heldRole, String mode, String severity) {
    }
}
