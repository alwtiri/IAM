package com.enterprise.iam.kernel;

import java.util.List;
import java.util.Objects;

/**
 * The single exception type crossing module and API boundaries. It carries an {@link ErrorCode} and a message that
 * is safe to show to end users (spec §73). The web layer maps it to {@link ApiError}; anything else becomes
 * {@code INTERNAL_ERROR} with a generic message.
 */
public final class IamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<ApiError.FieldIssue> details;
    private final boolean retryable;

    public IamException(ErrorCode code, String safeMessage) {
        this(code, safeMessage, List.of(), code.retryableByDefault(), null);
    }

    public IamException(ErrorCode code, String safeMessage, List<ApiError.FieldIssue> details, boolean retryable, Throwable cause) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"), cause, false, cause != null);
        this.code = Objects.requireNonNull(code, "code");
        this.details = details == null ? List.of() : List.copyOf(details);
        this.retryable = retryable;
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.FieldIssue> details() {
        return details;
    }

    public boolean retryable() {
        return retryable;
    }

    // ---- factories -------------------------------------------------------------------------------------------

    /** Also used when the caller may not see the object, to avoid enumeration (API-GUIDELINES §7). */
    public static IamException notFound(String what) {
        return new IamException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static IamException accessDenied() {
        return new IamException(ErrorCode.ACCESS_DENIED, "Access denied");
    }

    public static IamException stepUpRequired() {
        return new IamException(ErrorCode.STEP_UP_REQUIRED, "Re-authentication with multi-factor authentication is required");
    }

    public static IamException authenticationRequired() {
        return new IamException(ErrorCode.AUTHENTICATION_REQUIRED, "Authentication required");
    }

    public static IamException validation(String field, String code, String message) {
        return new IamException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                List.of(new ApiError.FieldIssue(field, code, message)), false, null);
    }

    public static IamException validation(List<ApiError.FieldIssue> issues) {
        return new IamException(ErrorCode.VALIDATION_FAILED, "Validation failed", issues, false, null);
    }

    public static IamException invalidTransition(String entity, Object from, Object to) {
        return new IamException(ErrorCode.INVALID_STATE_TRANSITION,
                entity + " cannot change from " + from + " to " + to);
    }

    public static IamException alreadyExists(String what) {
        return new IamException(ErrorCode.ALREADY_EXISTS, what + " already exists");
    }

    public static IamException concurrentModification(String what) {
        return new IamException(ErrorCode.CONCURRENT_MODIFICATION, what + " was modified concurrently; reload and retry");
    }

    public static IamException secretsUnavailable(Throwable cause) {
        return new IamException(ErrorCode.SECRETS_UNAVAILABLE, "The secret store is currently unavailable", List.of(), true, cause);
    }

    public static IamException dependencyUnavailable(String dependency, Throwable cause) {
        return new IamException(ErrorCode.DEPENDENCY_UNAVAILABLE, dependency + " is currently unavailable", List.of(), true, cause);
    }
}
