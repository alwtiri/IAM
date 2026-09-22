package com.enterprise.iam.core.operation.api;

import java.util.Map;
import java.util.UUID;

/**
 * Creates provider operations and hands them to the worker plane through the outbox. Both calls join the caller's
 * transaction, so the operation row, its credential handles (issued by the secrets module in between), the audit event,
 * and the outbox command commit together or not at all.
 */
public interface OperationCommands {

    /** Inserts the operation (QUEUED). Returns the existing operation id if the idempotency key was used before. */
    UUID create(ProviderCommand command);

    /** Writes the command message for an operation created by {@link #create}. {@code credentialHandles}: purpose → handle. */
    void dispatch(UUID operationId, ProviderCommand command, Map<String, String> credentialHandles);
}
