package com.enterprise.iam.core.operation.api;

/**
 * Delivers outbox messages of one destination kind. Implementations must be idempotent from the receiver's point of
 * view (message id is the de-duplication key) and must use bounded timeouts.
 */
public interface MessageDispatcher {

    /** Destination kind handled, e.g. {@code amqp} or {@code smtp}. */
    String kind();

    /** Delivers the message; throw to signal failure (the relay retries with back-off). */
    void dispatch(OutboxMessage message) throws Exception;
}
