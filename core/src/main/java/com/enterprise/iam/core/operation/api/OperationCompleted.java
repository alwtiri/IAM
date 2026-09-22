package com.enterprise.iam.core.operation.api;

import java.util.Map;
import java.util.UUID;

/**
 * Published (synchronously, inside the result transaction) when a worker result finished an operation. Listeners in
 * other modules (account) update their state from {@code resultPayload}, which never contains secrets.
 *
 * @param status final operation status: SUCCESS, FAILED, PARTIAL, TIMEOUT, UNKNOWN
 */
public record OperationCompleted(UUID operationId, String operation, String status, UUID providerInstanceId, UUID targetId,
                                 UUID accountId, Map<String, Object> resultPayload, String errorCode, String errorMessage) {

    public OperationCompleted {
        resultPayload = resultPayload == null ? Map.of() : resultPayload;
    }
}
