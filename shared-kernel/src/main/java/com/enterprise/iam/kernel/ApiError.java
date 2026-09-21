package com.enterprise.iam.kernel;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured error returned by every API surface (spec §73, ADR-0012).
 *
 * <p>{@code message} must be safe to show to end users: it never contains stack traces,
 * SQL, internal class or host names, or secret material.
 */
public record ApiError(
        ErrorCode code,
        String message,
        UUID operationId,
        UUID providerId,
        boolean retryable,
        Instant timestamp,
        String correlationId,
        List<FieldIssue> details) {

    public ApiError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timestamp, "timestamp");
        details = details == null ? List.of() : List.copyOf(details);
    }

    /** Creates an error with the code's default retryability and no operation/provider context. */
    public static ApiError of(ErrorCode code, String message, String correlationId, Instant now) {
        return new ApiError(code, message, null, null, code.retryableByDefault(), now, correlationId, List.of());
    }

    /** A single field-level validation problem. */
    public record FieldIssue(String field, String code, String message) {
        public FieldIssue {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(code, "code");
        }
    }
}
