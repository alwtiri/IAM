package com.enterprise.iam.core.operation.infrastructure.messaging;

import com.enterprise.iam.core.operation.application.OperationService;
import com.enterprise.iam.kernel.Json;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Consumes worker results from {@code iam.core.results} (bound to exchange {@code iam.ops.results}). Malformed messages
 * are rejected without requeue (dead-lettered); transient failures (database) are requeued by the container.
 */
public class OperationResultListener {

    private static final Logger log = LoggerFactory.getLogger(OperationResultListener.class);

    private final OperationService operations;

    public OperationResultListener(OperationService operations) {
        this.operations = operations;
    }

    @RabbitListener(queues = "${iam.operations.result-queue:iam.core.results}", concurrency = "${iam.operations.result-consumers:2}")
    public void onMessage(Message message) {
        OperationService.Result result;
        try {
            result = parse(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            log.warn("Rejecting malformed operation result message {}: {}", message.getMessageProperties().getMessageId(), e.getMessage());
            throw new AmqpRejectAndDontRequeueException("malformed operation result", e);
        }
        OperationService.Applied applied = operations.apply(result);
        if (applied != OperationService.Applied.APPLIED) {
            log.info("Operation result {} for {} not applied: {}", result.messageId(), result.operationId(), applied);
        }
    }

    @SuppressWarnings("unchecked")
    static OperationService.Result parse(String json) {
        Map<String, Object> m = Json.parseObject(json);
        Object schema = m.get("schemaVersion");
        if (!(schema instanceof Number n) || n.intValue() != 1) {
            throw new IllegalArgumentException("unsupported schemaVersion " + schema);
        }
        OperationService.Result.Verification v = null;
        if (m.get("verification") instanceof Map<?, ?> vm) {
            v = new OperationService.Result.Verification(str(vm, "mode"), Instant.parse(str(vm, "verifiedAt")), str(vm, "summary"));
        }
        OperationService.Result.Error e = null;
        if (m.get("error") instanceof Map<?, ?> em) {
            e = new OperationService.Result.Error(str(em, "code"), str(em, "message"), Boolean.TRUE.equals(em.get("retryable")),
                    Boolean.TRUE.equals(em.get("changeMayHaveApplied")));
        }
        String outcome = (String) m.get("outcome");
        if (!List.of("SUCCEEDED", "FAILED", "PARTIAL", "TIMEOUT", "UNKNOWN", "UNSUPPORTED", "PROGRESS").contains(outcome)) {
            throw new IllegalArgumentException("unknown outcome " + outcome);
        }
        Map<String, Object> payload = m.get("resultPayload") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
        int sequence = m.get("sequence") instanceof Number s ? s.intValue() : 0;
        return new OperationService.Result(UUID.fromString((String) m.get("messageId")), UUID.fromString((String) m.get("operationId")),
                ((Number) m.get("attempt")).intValue(), outcome, v, e, payload, sequence, String.valueOf(m.get("workerInstance")),
                m.get("completedAt") == null ? Instant.now() : Instant.parse((String) m.get("completedAt")));
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return v.toString();
    }
}
