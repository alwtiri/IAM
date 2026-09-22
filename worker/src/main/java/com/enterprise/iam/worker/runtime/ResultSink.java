package com.enterprise.iam.worker.runtime;

import java.util.Map;

/** Publishes operation result messages (contracts/messaging/operation-result.schema.json) to exchange iam.ops.results. */
@FunctionalInterface
public interface ResultSink {
    void publish(Map<String, Object> resultMessage);
}
