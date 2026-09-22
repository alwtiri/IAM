package com.enterprise.iam.core.request.api;

import java.util.List;
import java.util.UUID;

/**
 * Published when a request needs someone's decision or reaches an outcome. {@code recipients} lists the identities to inform:
 * the approvers of the current step while PENDING_APPROVAL, otherwise the requester.
 */
public record AccessRequestChanged(UUID requestId, UUID requesterId, String requesterName, String roleCode, String status, String reason,
                                   List<UUID> recipients) {

    public AccessRequestChanged {
        recipients = List.copyOf(recipients);
    }
}
