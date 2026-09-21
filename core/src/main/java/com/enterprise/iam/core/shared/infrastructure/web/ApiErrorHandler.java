package com.enterprise.iam.core.shared.infrastructure.web;

import com.enterprise.iam.kernel.ApiError;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.IamException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to the structured error model (spec §73). Never returns stack traces, exception class names,
 * SQL, or internal messages; unexpected exceptions are logged with the correlation id and returned as INTERNAL_ERROR.
 */
@RestControllerAdvice
class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);
    private final Clock clock;

    ApiErrorHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(IamException.class)
    ResponseEntity<ApiError> iam(IamException e, HttpServletRequest req) {
        if (e.code() == ErrorCode.INTERNAL_ERROR || e.code().httpStatus() >= 500) {
            log.warn("Request failed with {}: {}", e.code(), e.getMessage(), e.getCause());
        }
        return respond(new ApiError(e.code(), e.getMessage(), null, null, e.retryable(), now(), correlation(req), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<ApiError.FieldIssue> issues = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldIssue(f.getField(), f.getCode() == null ? "INVALID" : f.getCode().toUpperCase(),
                        f.getDefaultMessage()))
                .toList();
        return respond(new ApiError(ErrorCode.VALIDATION_FAILED, "Validation failed", null, null, false, now(), correlation(req), issues));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> unreadable(Exception e, HttpServletRequest req) {
        return respond(ApiError.of(ErrorCode.VALIDATION_FAILED, "The request is malformed", correlation(req), now()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest req) {
        return respond(ApiError.of(ErrorCode.ACCESS_DENIED, "Access denied", correlation(req), now()));
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ApiError> notFound(Exception e, HttpServletRequest req) {
        return respond(ApiError.of(ErrorCode.NOT_FOUND, "Resource not found", correlation(req), now()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled exception (correlationId={})", correlation(req), e);
        return respond(ApiError.of(ErrorCode.INTERNAL_ERROR, "An internal error occurred", correlation(req), now()));
    }

    private ResponseEntity<ApiError> respond(ApiError body) {
        return ResponseEntity.status(body.code().httpStatus()).body(body);
    }

    private Instant now() {
        return clock.instant();
    }

    private static String correlation(HttpServletRequest req) {
        Object id = req.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
