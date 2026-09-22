package com.enterprise.iam.core.operation.api;

import java.util.Map;
import java.util.UUID;

/** Intermediate result of a paged operation (e.g. one discovery page); the operation keeps RUNNING. */
public record OperationProgress(UUID operationId, String operation, UUID providerInstanceId, UUID targetId, int sequence,
                                Map<String, Object> resultPayload) {

    public OperationProgress {
        resultPayload = resultPayload == null ? Map.of() : resultPayload;
    }
}
