package com.enterprise.iam.core.operation.api;

import java.time.Instant;
import java.util.UUID;

/** Read model of an operation (spec §46, OpenAPI Operation). */
public record OperationView(UUID id, String type, UUID providerInstanceId, UUID targetId, UUID requesterId, UUID requestId,
                            String status, String statusReason, int progress, int attempt, int maxAttempts,
                            Instant createdAt, Instant startedAt, Instant finishedAt, Instant deadline,
                            String errorCode, String errorMessage, String verificationMode, Instant verifiedAt,
                            String verificationSummary, String correlationId) {
}
