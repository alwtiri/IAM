package com.enterprise.iam.core.account.api;

import java.util.UUID;

/** Published when a checkout ends. {@code status}: CHECKED_IN, EXPIRED or REVOKED. {@code requestId} may be null (direct checkout). */
public record CredentialCheckoutEnded(UUID checkoutId, UUID requestId, UUID accountId, String status) {
}
