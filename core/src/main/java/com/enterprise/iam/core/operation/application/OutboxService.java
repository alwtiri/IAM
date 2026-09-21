package com.enterprise.iam.core.operation.application;

import com.enterprise.iam.core.operation.api.OutboxMessage;
import com.enterprise.iam.core.operation.api.OutboxPublisher;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Writes outbox rows in the caller's transaction; adds correlation and message id headers. */
public class OutboxService implements OutboxPublisher {

    private static final Pattern DESTINATION = Pattern.compile("^(smtp|amqp:[a-z0-9.\\-]+/[a-z0-9.\\-#*]+)$");

    private final OutboxStore store;
    private final RequestContextProvider context;
    private final Clock clock;

    public OutboxService(OutboxStore store, RequestContextProvider context, Clock clock) {
        this.store = store;
        this.context = context;
        this.clock = clock;
    }

    @Override
    public UUID enqueue(String destination, String aggregateType, String aggregateId, Map<String, Object> payload,
                        Map<String, String> headers) {
        if (destination == null || !DESTINATION.matcher(destination).matches()) {
            throw new IllegalArgumentException("Invalid outbox destination: " + destination);
        }
        UUID id = Ids.newId(clock);
        Map<String, String> h = new HashMap<>(headers == null ? Map.of() : headers);
        h.put("messageId", id.toString());
        h.putIfAbsent("correlationId", context.current().correlationId());
        store.insert(new OutboxMessage(id, destination, aggregateType, aggregateId, payload, h, 0, clock.instant()), clock.instant());
        return id;
    }
}
