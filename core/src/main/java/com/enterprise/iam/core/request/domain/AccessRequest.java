package com.enterprise.iam.core.request.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A request for a time-bound role (type ROLE, spec §20) or a privileged credential checkout (type CREDENTIAL, ADR-0021:
 * {@code accountId}, {@code durationHours}; {@code roleCode} holds the account label and {@code roleAssignmentId} the
 * checkout). Decision snapshots are stored as JSON strings.
 */
public record AccessRequest(UUID id, UUID requesterId, UUID beneficiaryId, String type, UUID roleId, String roleCode, String scopeType,
                            String scopeValue, String justification, int durationDays, Status status, String statusReason,
                            String decisionJson, String sodConflictsJson, UUID roleAssignmentId, Instant validUntil, Instant createdAt,
                            Instant updatedAt, long version, UUID accountId, Integer durationHours) {

    public enum Status { PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED, ACTIVE, FAILED, EXPIRED, REVOKED }

    public AccessRequest withStatus(Status next, String reason, Instant now) {
        return new AccessRequest(id, requesterId, beneficiaryId, type, roleId, roleCode, scopeType, scopeValue, justification, durationDays,
                next, reason, decisionJson, sodConflictsJson, roleAssignmentId, validUntil, createdAt, now, version, accountId, durationHours);
    }

    public AccessRequest fulfilled(UUID assignmentId, Instant until, Instant now) {
        return new AccessRequest(id, requesterId, beneficiaryId, type, roleId, roleCode, scopeType, scopeValue, justification, durationDays,
                Status.ACTIVE, null, decisionJson, sodConflictsJson, assignmentId, until, createdAt, now, version, accountId,
                durationHours);
    }

    public boolean credential() {
        return "CREDENTIAL".equals(type);
    }

    public boolean open() {
        return status == Status.PENDING_APPROVAL || status == Status.APPROVED;
    }
}
