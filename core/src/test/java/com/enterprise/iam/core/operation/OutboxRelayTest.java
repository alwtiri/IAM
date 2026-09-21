package com.enterprise.iam.core.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.operation.api.MessageDispatcher;
import com.enterprise.iam.core.operation.api.OutboxMessage;
import com.enterprise.iam.core.operation.application.OutboxRelay;
import com.enterprise.iam.core.operation.application.OutboxService;
import com.enterprise.iam.core.operation.application.OutboxStore;
import com.enterprise.iam.core.operation.domain.RetryPolicy;
import com.enterprise.iam.core.testsupport.TestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RES1/RES2: a failing destination kind never blocks others and never loses messages. */
class OutboxRelayTest {

    static final class Row {
        OutboxMessage msg;
        String status = "PENDING";
        int attempts;
        Instant next;
        String error;
    }

    static final class MemoryOutbox implements OutboxStore {
        final Map<UUID, Row> rows = new LinkedHashMap<>();

        @Override
        public void insert(OutboxMessage m, Instant now) {
            Row r = new Row();
            r.msg = m;
            r.next = now;
            rows.put(m.id(), r);
        }

        @Override
        public List<OutboxMessage> claimDue(Instant now, Instant leaseUntil, int limit) {
            List<OutboxMessage> out = new ArrayList<>();
            for (Row r : rows.values()) {
                if (out.size() < limit && r.status.equals("PENDING") && !r.next.isAfter(now)) {
                    r.next = leaseUntil;
                    out.add(new OutboxMessage(r.msg.id(), r.msg.destination(), r.msg.aggregateType(), r.msg.aggregateId(),
                            r.msg.payload(), r.msg.headers(), r.attempts, r.msg.createdAt()));
                }
            }
            return out;
        }

        @Override
        public void markPublished(UUID id, Instant now) {
            rows.get(id).status = "PUBLISHED";
        }

        @Override
        public void markFailed(UUID id, int attempts, Instant nextAttemptAt, String error, boolean parked) {
            Row r = rows.get(id);
            r.attempts = attempts;
            r.next = nextAttemptAt;
            r.error = error;
            r.status = parked ? "PARKED" : "PENDING";
        }

        @Override
        public void release(UUID id, Instant nextAttemptAt) {
            rows.get(id).next = nextAttemptAt;
        }

        @Override
        public long pendingCount() {
            return rows.values().stream().filter(r -> r.status.equals("PENDING")).count();
        }

        long count(String status) {
            return rows.values().stream().filter(r -> r.status.equals(status)).count();
        }
    }

    static final class Switchable implements MessageDispatcher {
        final String kind;
        boolean up = true;
        final List<UUID> delivered = new ArrayList<>();

        Switchable(String kind) {
            this.kind = kind;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public void dispatch(OutboxMessage message) throws Exception {
            if (!up) {
                throw new java.net.ConnectException("connection refused");
            }
            delivered.add(message.id());
        }
    }

    private final TestSupport.MutableClock clock = new TestSupport.MutableClock(Instant.parse("2026-09-21T10:00:00Z"));
    private final MemoryOutbox store = new MemoryOutbox();
    private final OutboxService outbox = new OutboxService(store, TestSupport.CONTEXT, clock);
    private final Switchable amqp = new Switchable("amqp");
    private final Switchable smtp = new Switchable("smtp");
    private final OutboxRelay relay = new OutboxRelay(store, List.of(amqp, smtp), TestSupport.DIRECT_TX,
            new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 4), clock, Duration.ofSeconds(60), 50);

    @Test
    void brokerOutageDoesNotBlockEmailAndLosesNothing() {
        amqp.up = false;
        for (int i = 0; i < 3; i++) {
            outbox.enqueue("amqp:iam.events/identity.lifecycle", "identity", "i" + i, Map.of("n", i), Map.of());
        }
        outbox.enqueue("smtp", "notification", "n1", Map.of("to", "a@example.org"), Map.of());

        OutboxRelay.Pass pass = relay.relayOnce();
        assertEquals(1, smtp.delivered.size(), "email must flow while the broker is down");
        assertEquals(1, pass.failed(), "only the first AMQP message consumes an attempt");
        assertEquals(2, pass.deferred(), "other AMQP messages are deferred without consuming attempts");
        assertEquals(3, store.pendingCount());

        amqp.up = true;
        clock.advanceSeconds(120);
        relay.relayOnce();
        assertEquals(3, amqp.delivered.size(), "all messages delivered after recovery");
        assertEquals(0, store.pendingCount());
        assertEquals(4, store.count("PUBLISHED"));
    }

    @Test
    void messagesCarryCorrelationAndMessageIdHeaders() {
        UUID id = outbox.enqueue("smtp", "notification", "n1", Map.of(), Map.of());
        OutboxMessage m = store.rows.get(id).msg;
        assertEquals(id.toString(), m.headers().get("messageId"));
        assertEquals("test-correlation-1", m.headers().get("correlationId"));
    }

    @Test
    void persistentFailureParksAfterMaxAttempts() {
        amqp.up = false;
        UUID id = outbox.enqueue("amqp:iam.events/x", "a", "1", Map.of(), Map.of());
        for (int i = 0; i < 4; i++) {
            relay.relayOnce();
            clock.advanceSeconds(3600);
        }
        assertEquals("PARKED", store.rows.get(id).status);
        assertTrue(store.rows.get(id).error.contains("ConnectException"));
    }

    @Test
    void claimedButUnfinishedMessagesBecomeDueAgainAfterLease() {
        outbox.enqueue("smtp", "n", "1", Map.of(), Map.of());
        store.claimDue(clock.instant(), clock.instant().plusSeconds(60), 10); // simulate a relay that crashed after claiming
        assertEquals(0, relay.relayOnce().claimed());
        clock.advanceSeconds(61);
        assertEquals(1, relay.relayOnce().published());
    }

    @Test
    void unknownDestinationKindIsParkedAndInvalidDestinationsRejected() {
        store.insert(new OutboxMessage(UUID.randomUUID(), "fax:123", "x", "1", Map.of(), Map.of(), 0, clock.instant()), clock.instant());
        assertEquals(1, relay.relayOnce().parked());
        assertThrows(IllegalArgumentException.class, () -> outbox.enqueue("http://evil", "x", "1", Map.of(), Map.of()));
    }

    @Test
    void backoffGrowsAndIsCapped() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertEquals(Duration.ofSeconds(5), p.delayAfter(1));
        assertEquals(Duration.ofSeconds(40), p.delayAfter(4));
        assertEquals(Duration.ofMinutes(15), p.delayAfter(15));
    }
}
