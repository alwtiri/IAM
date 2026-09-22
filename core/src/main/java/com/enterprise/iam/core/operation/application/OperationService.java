package com.enterprise.iam.core.operation.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.operation.api.OperationCommands;
import com.enterprise.iam.core.operation.api.OperationCompleted;
import com.enterprise.iam.core.operation.api.OperationProgress;
import com.enterprise.iam.core.operation.api.OutboxPublisher;
import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.operation.domain.Operation;
import com.enterprise.iam.core.operation.domain.OperationStatus;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider operations on the worker plane (spec §46–§48, WORKER-ISOLATION.md): creation + outbox command, idempotent
 * application of worker results, and the deadline sweep.
 *
 * <p>Result rules (G5): a mutating operation is SUCCESS only with verification evidence; a success report without it
 * becomes UNKNOWN. A failure that may have changed the target also becomes UNKNOWN, never FAILED. Transient retries
 * happen inside the worker within the deadline; a finished operation is retried by creating a new attempt explicitly.
 */
public class OperationService implements OperationCommands {

    static final String CONSUMER = "core.operation-results";
    private static final CurrentActor SYSTEM = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);

    /** Parsed worker result (contracts/messaging/operation-result.schema.json). */
    public record Result(UUID messageId, UUID operationId, int attempt, String outcome, Verification verification, Error error,
                         Map<String, Object> resultPayload, int sequence, String workerInstance, Instant completedAt) {

        public record Verification(String mode, Instant verifiedAt, String summary) {
        }

        public record Error(String code, String message, boolean retryable, boolean changeMayHaveApplied) {
        }

        public Result {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(outcome, "outcome");
            resultPayload = resultPayload == null ? Map.of() : resultPayload;
        }
    }

    public enum Applied { APPLIED, DUPLICATE, UNKNOWN_OPERATION, STALE_ATTEMPT, ALREADY_FINISHED }

    private final OperationStore store;
    private final OutboxPublisher outbox;
    private final DomainEventPublisher events;
    private final AuditRecorder audit;
    private final RequestContextProvider context;
    private final TransactionRunner tx;
    private final Clock clock;

    public OperationService(OperationStore store, OutboxPublisher outbox, DomainEventPublisher events, AuditRecorder audit,
                            RequestContextProvider context, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.events = events;
        this.audit = audit;
        this.context = context;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public UUID create(ProviderCommand command) {
        return tx.inTransaction(() -> {
            Optional<UUID> existing = store.findByIdempotencyKey(command.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
            if (command.mutating() && command.accountId() != null && store.hasInFlightMutating(command.accountId())) {
                throw IamException.invalidTransition("Account", "operation in progress", command.operation());
            }
            UUID id = Ids.newId(clock);
            Instant now = clock.instant();
            Operation op = new Operation(id, command.operation(), command.mutating(), command.idempotencyKey(), command.maxAttempts());
            store.insert(op, command, now, now.plus(command.timeout()), context.current().correlationId());
            return id;
        });
    }

    @Override
    public void dispatch(UUID operationId, ProviderCommand command, Map<String, String> credentialHandles) {
        tx.run(() -> {
            OperationStore.Row row = store.lock(operationId).orElseThrow(() -> new IllegalStateException("operation not found: " + operationId));
            if (row.operation().status() != OperationStatus.QUEUED) {
                return; // idempotent re-submit of an operation that is already under way
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("schemaVersion", 1);
            msg.put("messageId", Ids.newId(clock).toString());
            msg.put("operationId", operationId.toString());
            msg.put("idempotencyKey", command.idempotencyKey());
            msg.put("providerType", command.providerType());
            msg.put("providerInstanceId", command.providerInstanceId().toString());
            if (command.targetId() != null) {
                msg.put("targetId", command.targetId().toString());
            }
            msg.put("operation", command.operation());
            msg.put("attempt", row.operation().attempt());
            msg.put("deadline", row.deadline().toString());
            msg.put("correlationId", context.current().correlationId());
            msg.put("issuedAt", clock.instant().toString());
            msg.put("credentialHandles", credentialHandles == null ? Map.of() : Map.copyOf(credentialHandles));
            msg.put("payload", command.payload());
            outbox.enqueue("amqp:iam.ops/" + command.queue(), "operation", operationId.toString(), msg, Map.of());
        });
    }

    // ------------------------------------------------------------------ results

    public Applied apply(Result r) {
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            if (!store.markProcessed(CONSUMER, r.messageId(), now)) {
                return Applied.DUPLICATE;
            }
            Optional<OperationStore.Row> found = store.lock(r.operationId());
            if (found.isEmpty()) {
                return Applied.UNKNOWN_OPERATION;
            }
            OperationStore.Row row = found.get();
            Operation op = row.operation();
            long version = op.version();
            if (r.attempt() != op.attempt()) {
                return Applied.STALE_ATTEMPT;
            }
            if (op.status() == OperationStatus.QUEUED) {
                op.start(now);
            }
            if (op.status() != OperationStatus.RUNNING) {
                return Applied.ALREADY_FINISHED;
            }
            if ("PROGRESS".equals(r.outcome())) {
                store.update(op, version, null);
                events.publish(new OperationProgress(op.id(), op.type(), row.providerInstanceId(), row.targetId(), r.sequence(),
                        r.resultPayload()));
                return Applied.APPLIED;
            }
            finish(op, r, now);
            Map<String, Object> response = new HashMap<>();
            response.put("outcome", r.outcome());
            response.put("worker", r.workerInstance());
            if (r.error() != null) {
                response.put("retryable", r.error().retryable());
                response.put("changeMayHaveApplied", r.error().changeMayHaveApplied());
            }
            if (!store.update(op, version, response)) {
                throw new IllegalStateException("concurrent update of operation " + op.id());
            }
            completed(op, row, r.resultPayload());
            return Applied.APPLIED;
        });
    }

    private static void finish(Operation op, Result r, Instant now) {
        String message = r.error() == null ? null : r.error().message();
        switch (r.outcome()) {
            case "SUCCEEDED" -> {
                if (op.mutating() && r.verification() == null) {
                    op.unknown("worker reported success without verification evidence", now);
                } else {
                    op.succeed(r.verification() == null ? null
                            : new Operation.Verification(r.verification().mode(), r.verification().verifiedAt(), r.verification().summary()), now);
                }
            }
            case "FAILED" -> {
                if (op.mutating() && r.error() != null && r.error().changeMayHaveApplied()) {
                    op.unknown("failure after the change may have been applied: " + message, now);
                } else {
                    op.fail(r.error() == null ? "PROVIDER_ERROR" : r.error().code(), message, now);
                }
            }
            case "UNSUPPORTED" -> op.fail("UNSUPPORTED_CAPABILITY", message, now);
            case "TIMEOUT" -> op.timeout(message == null ? "provider call timed out" : message, now);
            case "PARTIAL" -> op.partial(message, now);
            default -> op.unknown(message == null ? "outcome " + r.outcome() : message, now);
        }
    }

    // ------------------------------------------------------------------ deadline sweep

    /** Moves QUEUED/RUNNING operations past their deadline to TIMEOUT. Safe to run on every replica. */
    public int sweepOverdue(int limit) {
        List<UUID> overdue = tx.readOnly(() -> store.findOverdue(clock.instant(), limit));
        int n = 0;
        for (UUID id : overdue) {
            n += tx.inTransaction(() -> {
                OperationStore.Row row = store.lock(id).orElse(null);
                if (row == null) {
                    return 0;
                }
                Operation op = row.operation();
                long version = op.version();
                Instant now = clock.instant();
                if ((op.status() != OperationStatus.QUEUED && op.status() != OperationStatus.RUNNING) || row.deadline() == null
                        || row.deadline().isAfter(now)) {
                    return 0;
                }
                op.timeout(op.status() == OperationStatus.QUEUED ? "no worker picked up the operation before its deadline"
                        : "no result before the deadline", now);
                store.update(op, version, Map.of("outcome", "DEADLINE_EXCEEDED"));
                completed(op, row, Map.of());
                return 1;
            });
        }
        return n;
    }

    private void completed(Operation op, OperationStore.Row row, Map<String, Object> payload) {
        audit.record(SYSTEM, new AuditEntry("operation.completed", "operation", op.id().toString(), row.targetId(),
                op.status() == OperationStatus.SUCCESS ? AuditEntry.Result.SUCCESS : AuditEntry.Result.FAILURE, op.errorMessage(),
                row.providerInstanceId(), Map.of("type", op.type(), "status", op.status().name(),
                        "attempt", String.valueOf(op.attempt()), "errorCode", String.valueOf(op.errorCode()))));
        // The result payload travels to listeners (e.g. account state import) but is never persisted as a whole.
        events.publish(new OperationCompleted(op.id(), op.type(), op.status().name(), row.providerInstanceId(), row.targetId(),
                row.accountId(), payload, op.errorCode(), op.errorMessage()));
    }
}
