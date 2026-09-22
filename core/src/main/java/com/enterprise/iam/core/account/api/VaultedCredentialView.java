package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A privileged account whose password the platform holds in Vault. Never contains the password.
 *
 * @param rotationStatus ROTATING, VERIFIED (target and Vault agree), UNKNOWN (change sent, not confirmed), FAILED, NONE
 */
public record VaultedCredentialView(UUID accountId, String accountName, UUID targetId, String targetName, String providerType,
                                    boolean privileged, String rotationStatus, String rotationTrigger, Instant lastRotatedAt,
                                    String lastRotationError, UUID rotationOperationId, Instant nextRotationAt, CheckoutView activeCheckout,
                                    boolean emergency) {
}
