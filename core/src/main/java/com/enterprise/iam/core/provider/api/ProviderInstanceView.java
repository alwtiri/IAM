package com.enterprise.iam.core.provider.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Provider instance metadata. {@code credentialConfigured} replaces any credential detail; the value is only in Vault. */
public record ProviderInstanceView(UUID id, String type, String name, String endpoint, Map<String, String> settings,
                                   boolean credentialConfigured, boolean enabled, String health, String circuitState,
                                   Instant lastHealthAt, String failureReason, long version) {
}
