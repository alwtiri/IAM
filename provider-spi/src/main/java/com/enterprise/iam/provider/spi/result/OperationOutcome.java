package com.enterprise.iam.provider.spi.result;

/**
 * Outcome of a provider call. The Core maps it to the Operation status (spec §46):
 * SUCCEEDED→SUCCESS, FAILED→FAILED, PARTIAL→PARTIAL, TIMEOUT→TIMEOUT, UNKNOWN→UNKNOWN,
 * UNSUPPORTED→FAILED with error code UNSUPPORTED_CAPABILITY.
 */
public enum OperationOutcome {
    /** Completed; for mutating operations the effect was verified. */
    SUCCEEDED,
    /** Failed; the provider asserts that no change was applied. */
    FAILED,
    /** Some steps verified, others not; details describe which. */
    PARTIAL,
    /** Deadline reached; the change may or may not have been applied. */
    TIMEOUT,
    /** The request was sent but the outcome could not be verified. Requires recovery verification. */
    UNKNOWN,
    /** The provider does not support the requested capability (UNSUPPORTED_CAPABILITY). */
    UNSUPPORTED
}
