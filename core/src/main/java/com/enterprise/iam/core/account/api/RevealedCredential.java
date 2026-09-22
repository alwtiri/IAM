package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The password of a checked-out account, returned only to the checkout holder after step-up (never logged or cached).
 * When the last rotation could not be confirmed, {@code alternatePassword} holds the value that may be in effect instead.
 */
public record RevealedCredential(UUID checkoutId, String accountName, String targetName, String password, String alternatePassword,
                                 Instant notAfter) {

    @Override
    public String toString() {
        return "RevealedCredential[" + accountName + " @ " + targetName + ", ***]";
    }
}
