package com.enterprise.iam.core.operation.application;

import com.enterprise.iam.core.operation.api.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port of the transactional outbox. */
public interface OutboxStore {

    void insert(OutboxMessage message, Instant now);

    /**
     * Claims up to {@code limit} due PENDING messages (FOR UPDATE SKIP LOCKED) by moving their next attempt time to
     * {@code leaseUntil}; a crashed relay's claim therefore expires automatically (at-least-once delivery).
     */
    List<OutboxMessage> claimDue(Instant now, Instant leaseUntil, int limit);

    void markPublished(UUID id, Instant now);

    /** Records a failed attempt; {@code parked} stops further retries. */
    void markFailed(UUID id, int attempts, Instant nextAttemptAt, String error, boolean parked);

    /** Returns a claimed message to the queue without counting an attempt. */
    void release(UUID id, Instant nextAttemptAt);

    long pendingCount();
}
