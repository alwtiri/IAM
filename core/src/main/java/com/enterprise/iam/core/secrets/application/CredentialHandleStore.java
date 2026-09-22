package com.enterprise.iam.core.secrets.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CredentialHandleStore {

    record Row(UUID operationId, String purpose, String secretRef, String providerType, Instant expiresAt, Instant redeemedAt) {
    }

    void insert(byte[] handleHash, UUID operationId, String purpose, String secretRef, String providerType, Instant issuedAt, Instant expiresAt);

    /** Loads and row-locks a handle by hash. */
    Optional<Row> lock(byte[] handleHash);

    void markRedeemed(byte[] handleHash, String redeemedBy, Instant at);
}
