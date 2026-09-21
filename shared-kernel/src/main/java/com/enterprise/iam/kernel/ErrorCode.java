package com.enterprise.iam.kernel;

/**
 * Platform-wide error codes (spec §73, ADR-0012).
 *
 * <p>This enum is the authoritative list. The {@code ErrorCode} schema in
 * {@code contracts/openapi/iam-core-v1.yaml} must contain exactly the same values;
 * a build check keeps them in sync. New codes may be added (additive change);
 * existing codes are never renamed or removed within API major version 1.
 */
public enum ErrorCode {

    VALIDATION_FAILED(400, false),
    AUTHENTICATION_REQUIRED(401, false),
    ACCESS_DENIED(403, false),
    STEP_UP_REQUIRED(403, false),
    POLICY_DENIED(403, false),
    SOD_CONFLICT(403, false),
    NOT_FOUND(404, false),
    CONCURRENT_MODIFICATION(409, true),
    INVALID_STATE_TRANSITION(409, false),
    ALREADY_EXISTS(409, false),
    IDEMPOTENCY_KEY_REUSED(422, false),
    UNSUPPORTED_CAPABILITY(422, false),
    RATE_LIMITED(429, true),
    PROVIDER_UNAVAILABLE(503, true),
    SECRETS_UNAVAILABLE(503, true),
    AUTHENTICATION_UNAVAILABLE(503, true),
    DEPENDENCY_UNAVAILABLE(503, true),
    OPERATION_TIMEOUT(504, true),
    INTERNAL_ERROR(500, false);

    private final int httpStatus;
    private final boolean retryableByDefault;

    ErrorCode(int httpStatus, boolean retryableByDefault) {
        this.httpStatus = httpStatus;
        this.retryableByDefault = retryableByDefault;
    }

    /** HTTP status used when this code is returned by the public API. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Whether a client may retry the same request unchanged, unless overridden per error. */
    public boolean retryableByDefault() {
        return retryableByDefault;
    }
}
