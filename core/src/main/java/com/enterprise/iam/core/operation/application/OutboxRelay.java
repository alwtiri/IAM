package com.enterprise.iam.core.operation.application;

import com.enterprise.iam.core.operation.api.MessageDispatcher;
import com.enterprise.iam.core.operation.api.OutboxMessage;
import com.enterprise.iam.core.operation.domain.RetryPolicy;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers outbox messages (ADR-0006, PHASE-2-DESIGN §3.5).
 *
 * <ol>
 *   <li>Claim a batch in a short transaction (no network I/O while holding row locks).</li>
 *   <li>Dispatch each message outside any transaction.</li>
 *   <li>Record the outcome per message in its own short transaction.</li>
 * </ol>
 * A failure of one destination kind (e.g. RabbitMQ down) makes the relay skip that kind for the rest of the batch
 * without consuming attempts, so other kinds (e.g. SMTP) keep flowing (RES1, RES2).
 */
public class OutboxRelay {

    private static final Logger LOG = Logger.getLogger(OutboxRelay.class.getName());

    private final OutboxStore store;
    private final Map<String, MessageDispatcher> dispatchers = new HashMap<>();
    private final TransactionRunner tx;
    private final RetryPolicy retry;
    private final Clock clock;
    private final Duration lease;
    private final int batchSize;

    public OutboxRelay(OutboxStore store, List<MessageDispatcher> dispatchers, TransactionRunner tx, RetryPolicy retry,
                       Clock clock, Duration lease, int batchSize) {
        this.store = store;
        dispatchers.forEach(d -> this.dispatchers.put(d.kind(), d));
        this.tx = tx;
        this.retry = retry;
        this.clock = clock;
        this.lease = lease;
        this.batchSize = batchSize;
    }

    /** Result counters of one relay pass. */
    public record Pass(int claimed, int published, int failed, int deferred, int parked) {
    }

    public Pass relayOnce() {
        Instant now = clock.instant();
        List<OutboxMessage> batch = tx.inTransaction(() -> store.claimDue(now, now.plus(lease), batchSize));
        int published = 0;
        int failed = 0;
        int deferred = 0;
        int parked = 0;
        Set<String> failingKinds = new HashSet<>();
        for (OutboxMessage m : batch) {
            MessageDispatcher d = dispatchers.get(m.kind());
            if (d == null) {
                tx.run(() -> store.markFailed(m.id(), m.attempts() + 1, now, "no dispatcher for destination kind " + m.kind(), true));
                parked++;
                continue;
            }
            if (failingKinds.contains(m.kind())) {
                Instant soon = clock.instant().plus(retry.base());
                tx.run(() -> store.release(m.id(), soon));
                deferred++;
                continue;
            }
            try {
                d.dispatch(m);
                Instant done = clock.instant();
                tx.run(() -> store.markPublished(m.id(), done));
                published++;
            } catch (Exception e) {
                failingKinds.add(m.kind());
                int attempts = m.attempts() + 1;
                boolean park = retry.exhausted(attempts);
                Instant next = clock.instant().plus(retry.delayAfter(attempts));
                String error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + truncate(e.getMessage()));
                tx.run(() -> store.markFailed(m.id(), attempts, next, error, park));
                if (park) {
                    parked++;
                    LOG.log(Level.WARNING, "Outbox message {0} parked after {1} attempts", new Object[]{m.id(), attempts});
                } else {
                    failed++;
                }
            }
        }
        return new Pass(batch.size(), published, failed, deferred, parked);
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
