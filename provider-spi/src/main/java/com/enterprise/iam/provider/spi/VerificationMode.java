package com.enterprise.iam.provider.spi;

/** How a provider verifies the effect of a mutating operation (spec §46). */
public enum VerificationMode {
    /** Independent read-back of target state after the change. */
    READ_BACK,
    /** Authentication test with the new credential (password/key operations). */
    LOGIN_TEST,
    /** Target reports the change through an authoritative, separately queried API or audit record. */
    TARGET_CONFIRMATION,
    /** Verification is technically impossible; results can never be SUCCEEDED, only UNKNOWN. */
    NOT_POSSIBLE
}
