package com.enterprise.iam.core.provider.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider instance lookups for other modules (account operations). No authorization check: callers enforce their own.
 * {@code credentialSecretRef} is a Vault reference, never secret material.
 */
public interface ProviderDirectory {

    record Connection(UUID id, String type, String endpoint, Map<String, String> settings, String credentialSecretRef, boolean enabled) {
    }

    Optional<Connection> connection(UUID providerInstanceId);

    boolean isBound(UUID targetId, UUID providerInstanceId);
}
