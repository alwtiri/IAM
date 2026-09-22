package com.enterprise.iam.core.account.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Credential checkout for other modules (the request module fulfils approved CREDENTIAL requests through it, ADR-0021).
 * A checkout is exclusive and time-bound; the password is rotated when it is checked in or expires.
 */
public interface CredentialCheckouts {

    /** A vaulted account that can be requested. {@code available} is false while it is checked out or not yet verified. */
    record CheckoutTarget(UUID accountId, String accountName, UUID targetId, String targetName, String providerType, boolean available,
                          String unavailableReason) {

        public String label() {
            return accountName + " @ " + targetName;
        }
    }

    List<CheckoutTarget> checkoutTargets();

    Optional<CheckoutTarget> checkoutTarget(UUID accountId);

    /**
     * Opens a checkout for an approved request (runs as the platform).
     *
     * @return checkout id
     * @throws com.enterprise.iam.kernel.IamException when the account is not vaulted, not verified, or already checked out
     */
    UUID grant(UUID accountId, UUID identityId, UUID requestId, Duration duration, String reason);
}
