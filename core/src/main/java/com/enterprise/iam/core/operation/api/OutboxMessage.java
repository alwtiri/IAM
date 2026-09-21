package com.enterprise.iam.core.operation.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A message recorded in the transactional outbox (ADR-0006). {@code destination} is {@code amqp:<exchange>/<routingKey>}
 * or {@code smtp}. Payloads contain no secret values.
 */
public record OutboxMessage(UUID id, String destination, String aggregateType, String aggregateId,
                            Map<String, Object> payload, Map<String, String> headers, int attempts, Instant createdAt) {

    public OutboxMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(destination, "destination");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Destination kind: the part before the first colon ({@code amqp}, {@code smtp}). */
    public String kind() {
        int i = destination.indexOf(':');
        return i < 0 ? destination : destination.substring(0, i);
    }

    /** Destination address after the kind, e.g. {@code iam.events/identity.lifecycle}; empty if none. */
    public String address() {
        int i = destination.indexOf(':');
        return i < 0 ? "" : destination.substring(i + 1);
    }
}
