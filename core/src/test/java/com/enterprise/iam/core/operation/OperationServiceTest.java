package com.enterprise.iam.core.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.operation.api.OperationCompleted;
import com.enterprise.iam.core.operation.api.OperationProgress;
import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.operation.application.OperationService;
import com.enterprise.iam.core.operation.application.OperationStore;
import com.enterprise.iam.core.operation.domain.Operation;
import com.enterprise.iam.core.operation.domain.OperationStatus;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.IamException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Worker results: idempotent, attempt-bound, G5 verification rule, deadline sweep (WORKER-ISOLATION §6, §8). */
class OperationServiceTest {

    static final class MemoryOps implements OperationStore {
        final Map<UUID, Row> rows = new HashMap<>();
        final Map<String, UUID> keys = new HashMap<>();
        final Set<String> processed = new HashSet<>();
        final Map<UUID, Map<String, Object>> responses = new HashMap<>();

        @Override
        public void insert(Operation op, ProviderCommand c, Instant createdAt, Instant deadline, String correlationId) {
            rows.put(op.id(), new Row(op, c.providerType(), c.providerInstanceId(), c.targetId(), c.accountId(), deadline));
            keys.put(op.idempotencyKey(), op.id());
        }

        @Override
        public Optional<UUID> findByIdempotencyKey(String key) {
            return Optional.ofNullable(keys.get(key));
        }

        @Override
        public Optional<Row> lock(UUID id) {
            Row r = rows.get(id);
            if (r == null) {
                return Optional.empty();
            }
            Operation o = r.operation();
            // hand out a copy, like a database read would
            Operation copy = new Operation(o.id(), o.type(), o.mutating(), o.idempotencyKey(), o.status(), o.statusReason(), o.attempt(),
                    o.maxAttempts(), o.startedAt(), o.finishedAt(), o.errorCode(), o.errorMessage(), o.verification(), o.version());
            return Optional.of(new Row(copy, r.providerType(), r.providerInstanceId(), r.targetId(), r.accountId(), r.deadline()));
        }

        @Override
        public boolean update(Operation op, long expectedVersion, Map<String, Object> providerResponse) {
            Row r = rows.get(op.id());
            if (r.operation().version() != expectedVersion) {
                return false;
            }
            Operation next = new Operation(op.id(), op.type(), op.mutating(), op.idempotencyKey(), op.status(), op.statusReason(), op.attempt(),
                    op.maxAttempts(), op.startedAt(), op.finishedAt(), op.errorCode(), op.errorMessage(), op.verification(), expectedVersion + 1);
            rows.put(op.id(), new Row(next, r.providerType(), r.providerInstanceId(), r.targetId(), r.accountId(), r.deadline()));
            if (providerResponse != null) {
                responses.put(op.id(), providerResponse);
            }
            return true;
        }

        @Override
        public boolean markProcessed(String consumer, UUID messageId, Instant at) {
            return processed.add(consumer + messageId);
        }

        @Override
        public List<UUID> findOverdue(Instant now, int limit) {
            return rows.values().stream().filter(r -> (r.operation().status() == OperationStatus.QUEUED
                    || r.operation().status() == OperationStatus.RUNNING) && r.deadline().isBefore(now)).map(r -> r.operation().id()).toList();
        }

        @Override
        public boolean hasInFlightMutating(UUID accountId) {
            return rows.values().stream().anyMatch(r -> accountId.equals(r.accountId()) && r.operation().mutating()
                    && Set.of(OperationStatus.QUEUED, OperationStatus.RUNNING, OperationStatus.UNKNOWN, OperationStatus.PARTIAL)
                    .contains(r.operation().status()));
        }

        OperationStatus status(UUID id) {
            return rows.get(id).operation().status();
        }
    }

    MemoryOps store;
    List<Object> events;
    List<Map<String, Object>> outbox;
    List<AuditEntry> audited;
    TestSupport.MutableClock clock;
    OperationService service;

    @BeforeEach
    void setUp() {
        store = new MemoryOps();
        events = new ArrayList<>();
        outbox = new ArrayList<>();
        audited = new ArrayList<>();
        clock = new TestSupport.MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
        service = new OperationService(store, (dest, type, id, payload, headers) -> {
            outbox.add(Map.of("dest", dest, "payload", payload));
            return UUID.randomUUID();
        }, events::add, (actor, e) -> audited.add(e), TestSupport.CONTEXT, TestSupport.DIRECT_TX, clock);
    }

    static ProviderCommand cmd(String op, boolean mutating, UUID account, String key) {
        return new ProviderCommand(op, mutating, "linux-ssh", UUID.randomUUID(), UUID.randomUUID(), account, UUID.randomUUID(), "/o/",
                "PRODUCTION", Map.of("account", Map.of("name", "alice")), Duration.ofMinutes(2), 1, key);
    }

    static OperationService.Result result(UUID op, String outcome, boolean verified, OperationService.Result.Error error) {
        return new OperationService.Result(UUID.randomUUID(), op, 1, outcome,
                verified ? new OperationService.Result.Verification("READ_BACK", Instant.parse("2026-09-22T10:00:05Z"), "account locked") : null,
                error, Map.of("state", Map.of("account", Map.of("name", "alice"))), 0, "worker-1", Instant.parse("2026-09-22T10:00:05Z"));
    }

    @Test
    void createIsIdempotentAndDispatchWritesTheCommandToTheProviderQueue() {
        ProviderCommand c = cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-1:v3");
        UUID a = service.create(c);
        UUID b = service.create(c);
        assertEquals(a, b);
        service.dispatch(a, c, Map.of("connection", "ch_abc"));
        assertEquals(1, outbox.size());
        assertEquals("amqp:iam.ops/ops.linux-ssh", outbox.get(0).get("dest"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) outbox.get(0).get("payload");
        assertEquals("DISABLE_ACCOUNT", payload.get("operation"));
        assertEquals(Map.of("connection", "ch_abc"), payload.get("credentialHandles"));
    }

    @Test
    void secondMutatingOperationOnTheSameAccountIsRejectedWhileOneIsInFlight() {
        UUID account = UUID.randomUUID();
        service.create(cmd("DISABLE_ACCOUNT", true, account, "disable:acc-1:v3"));
        IamException e = assertThrows(IamException.class, () -> service.create(cmd("ENABLE_ACCOUNT", true, account, "enable:acc-1:v3")));
        assertEquals("INVALID_STATE_TRANSITION", e.code().name());
    }

    @Test
    void verifiedSuccessCompletesAndPublishesTheResultPayload() {
        UUID op = service.create(cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-2:v1"));
        assertEquals(OperationService.Applied.APPLIED, service.apply(result(op, "SUCCEEDED", true, null)));
        assertEquals(OperationStatus.SUCCESS, store.status(op));
        OperationCompleted done = (OperationCompleted) events.get(0);
        assertEquals("SUCCESS", done.status());
        assertTrue(done.resultPayload().containsKey("state"));
        assertTrue(audited.stream().anyMatch(e -> e.action().equals("operation.completed")));
    }

    @Test
    void mutatingSuccessWithoutVerificationBecomesUnknown() {
        UUID op = service.create(cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-3:v1"));
        service.apply(result(op, "SUCCEEDED", false, null));
        assertEquals(OperationStatus.UNKNOWN, store.status(op));
    }

    @Test
    void failureThatMayHaveAppliedBecomesUnknownOtherwiseFailed() {
        UUID a = service.create(cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-4:v1"));
        service.apply(result(a, "FAILED", false, new OperationService.Result.Error("PROVIDER_UNAVAILABLE", "connection reset", true, true)));
        assertEquals(OperationStatus.UNKNOWN, store.status(a));
        UUID b = service.create(cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-5:v1"));
        service.apply(result(b, "FAILED", false, new OperationService.Result.Error("PROVIDER_UNAVAILABLE", "refused", true, false)));
        assertEquals(OperationStatus.FAILED, store.status(b));
    }

    @Test
    void duplicateAndStaleResultsAreIgnored() {
        UUID op = service.create(cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-6:v1"));
        OperationService.Result r = result(op, "SUCCEEDED", true, null);
        service.apply(r);
        assertEquals(OperationService.Applied.DUPLICATE, service.apply(r));
        OperationService.Result stale = new OperationService.Result(UUID.randomUUID(), op, 7, "FAILED", null,
                new OperationService.Result.Error("X", "late", false, false), Map.of(), 0, "w", Instant.now());
        assertEquals(OperationService.Applied.STALE_ATTEMPT, service.apply(stale));
        assertEquals(OperationStatus.SUCCESS, store.status(op));
        assertEquals(OperationService.Applied.UNKNOWN_OPERATION, service.apply(result(UUID.randomUUID(), "SUCCEEDED", true, null)));
    }

    @Test
    void progressKeepsTheOperationRunningAndPublishesPages() {
        UUID op = service.create(cmd("DISCOVER_ACCOUNTS", false, null, "discover:t1:run1"));
        OperationService.Result page = new OperationService.Result(UUID.randomUUID(), op, 1, "PROGRESS", null, null,
                Map.of("accounts", List.of()), 0, "w", Instant.now());
        service.apply(page);
        assertEquals(OperationStatus.RUNNING, store.status(op));
        assertTrue(events.get(0) instanceof OperationProgress);
        service.apply(result(op, "SUCCEEDED", false, null));
        assertEquals(OperationStatus.SUCCESS, store.status(op), "non-mutating operations do not need verification");
    }

    @Test
    void deadlineSweepTimesOutQueuedAndRunningOperations() {
        UUID queued = service.create(cmd("DISABLE_ACCOUNT", true, UUID.randomUUID(), "disable:acc-7:v1"));
        UUID fresh = service.create(new ProviderCommand("GET_ACCOUNT_STATE", false, "linux-ssh", UUID.randomUUID(), null, null, null, null, null,
                Map.of(), Duration.ofHours(1), 1, "state:acc-8:v1"));
        clock.advanceSeconds(180);
        assertEquals(1, service.sweepOverdue(10));
        assertEquals(OperationStatus.TIMEOUT, store.status(queued));
        assertEquals(OperationStatus.QUEUED, store.status(fresh));
        assertEquals("TIMEOUT", ((OperationCompleted) events.get(0)).status());
        assertFalse(store.hasInFlightMutating(UUID.randomUUID()));
    }

    @Test
    void commandRejectsSecretLikePayloadKeys() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderCommand("RESET_PASSWORD", true, "linux-ssh", UUID.randomUUID(), null, null,
                null, null, null, Map.of("newPassword", "x"), null, 1, "reset:acc:v1"));
    }
}
