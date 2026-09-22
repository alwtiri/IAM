package com.enterprise.iam.core.operation.domain;

import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Long-running operation aggregate (spec §46–§47). Enforces the lifecycle and the rule that SUCCESS requires a
 * verification (gate clarification G5) and that retries stay within {@code maxAttempts}.
 */
public final class Operation {

    /** Evidence of verified target state. */
    public record Verification(String mode, Instant verifiedAt, String summary) {
        public Verification {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(verifiedAt, "verifiedAt");
            Objects.requireNonNull(summary, "summary");
        }
    }

    private final UUID id;
    private final String type;
    private final boolean mutating;
    private final String idempotencyKey;
    private OperationStatus status;
    private String statusReason;
    private int attempt;
    private final int maxAttempts;
    private Instant startedAt;
    private Instant finishedAt;
    private String errorCode;
    private String errorMessage;
    private Verification verification;
    private long version;

    public Operation(UUID id, String type, boolean mutating, String idempotencyKey, int maxAttempts) {
        this(id, type, mutating, idempotencyKey, OperationStatus.QUEUED, null, 1, maxAttempts, null, null, null, null, null, 0);
    }

    public Operation(UUID id, String type, boolean mutating, String idempotencyKey, OperationStatus status, String statusReason,
                     int attempt, int maxAttempts, Instant startedAt, Instant finishedAt, String errorCode, String errorMessage,
                     Verification verification, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.mutating = mutating;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.status = Objects.requireNonNull(status, "status");
        this.statusReason = statusReason;
        this.attempt = attempt;
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.verification = verification;
        this.version = version;
    }

    public void start(Instant now) {
        transition(OperationStatus.RUNNING);
        startedAt = now;
        statusReason = null;
    }

    public void succeed(Verification v, Instant now) {
        if (mutating && v == null) {
            throw new IllegalStateException("SUCCESS requires verification for mutating operation " + type);
        }
        transition(OperationStatus.SUCCESS);
        verification = v;
        finish(now, null, null);
    }

    public void fail(String code, String message, Instant now) {
        transition(OperationStatus.FAILED);
        finish(now, code, message);
    }

    public void timeout(String message, Instant now) {
        transition(OperationStatus.TIMEOUT);
        finish(now, "OPERATION_TIMEOUT", message);
    }

    /** Some sub-steps succeeded, others did not; the outcome needs attention (e.g. a paged run stopped midway). */
    public void partial(String message, Instant now) {
        transition(OperationStatus.PARTIAL);
        finish(now, "PARTIAL", message);
    }

    public void unknown(String message, Instant now) {
        transition(OperationStatus.UNKNOWN);
        finish(now, "OUTCOME_UNVERIFIED", message);
    }

    public void cancel(Instant now) {
        transition(OperationStatus.CANCELLED);
        finish(now, null, "cancelled");
    }

    /** Re-queues a failed or timed-out operation if attempts remain. */
    public void retry() {
        if (attempt >= maxAttempts) {
            throw IamException.invalidTransition("Operation", status, "QUEUED (no attempts left)");
        }
        transition(OperationStatus.QUEUED);
        attempt++;
        finishedAt = null;
    }

    public void queuedReason(String reason) {
        if (status != OperationStatus.QUEUED) {
            throw new IllegalStateException("reason only applies to queued operations");
        }
        statusReason = reason;
    }

    private void transition(OperationStatus next) {
        if (!status.canTransitionTo(next)) {
            throw IamException.invalidTransition("Operation", status, next);
        }
        status = next;
    }

    private void finish(Instant now, String code, String message) {
        finishedAt = now;
        errorCode = code;
        errorMessage = message;
    }

    public UUID id() { return id; }
    public String type() { return type; }
    public boolean mutating() { return mutating; }
    public String idempotencyKey() { return idempotencyKey; }
    public OperationStatus status() { return status; }
    public String statusReason() { return statusReason; }
    public int attempt() { return attempt; }
    public int maxAttempts() { return maxAttempts; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public Verification verification() { return verification; }
    public long version() { return version; }
}
