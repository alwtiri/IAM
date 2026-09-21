package com.enterprise.iam.provider.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Execution context passed with every provider call.
 *
 * @param operationId    Core operation id (spec §46)
 * @param idempotencyKey retries of the same logical operation carry the same key (spec §47)
 * @param correlationId  propagated correlation id (spec §67)
 * @param attempt        1-based attempt number
 * @param deadline       absolute deadline; providers must stop and return TIMEOUT beyond it
 * @param credentials    resolver for credential handles
 */
public record OperationContext(
        UUID operationId,
        String idempotencyKey,
        String correlationId,
        int attempt,
        Instant deadline,
        CredentialResolver credentials) {

    public OperationContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(credentials, "credentials");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(deadline);
    }
}
