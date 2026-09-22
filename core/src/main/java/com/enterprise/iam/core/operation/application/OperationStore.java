package com.enterprise.iam.core.operation.application;

import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.operation.domain.Operation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Write-side persistence of operations (the read side is {@code OperationReader}). */
public interface OperationStore {

    record Row(Operation operation, String providerType, UUID providerInstanceId, UUID targetId, UUID accountId, Instant deadline) {
    }

    void insert(Operation operation, ProviderCommand command, Instant createdAt, Instant deadline, String correlationId);

    Optional<UUID> findByIdempotencyKey(String idempotencyKey);

    /** Loads and row-locks the operation for the rest of the transaction. */
    Optional<Row> lock(UUID id);

    boolean update(Operation operation, long expectedVersion, Map<String, Object> providerResponse);

    /** @return true if this (consumer, message) pair was not processed before */
    boolean markProcessed(String consumer, UUID messageId, Instant at);

    List<UUID> findOverdue(Instant now, int limit);

    /** True while a mutating operation on the account is QUEUED/RUNNING/UNKNOWN/PARTIAL (DOMAIN-MODEL §9). */
    boolean hasInFlightMutating(UUID accountId);
}
