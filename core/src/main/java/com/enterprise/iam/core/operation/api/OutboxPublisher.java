package com.enterprise.iam.core.operation.api;

import java.util.Map;
import java.util.UUID;

/** Records an outgoing message in the caller's transaction; delivery happens asynchronously (RES1). */
public interface OutboxPublisher {

    UUID enqueue(String destination, String aggregateType, String aggregateId, Map<String, Object> payload, Map<String, String> headers);
}
