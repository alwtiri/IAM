package com.enterprise.iam.provider.spi;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Connection parameters of one provider instance. {@code settings} must never contain secret values;
 * credentials are referenced only through {@link #credential()}.
 */
public record ProviderConnection(
        UUID providerInstanceId,
        ProviderTypeId type,
        String endpoint,
        Map<String, String> settings,
        CredentialHandle credential) {

    public ProviderConnection {
        Objects.requireNonNull(providerInstanceId, "providerInstanceId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(endpoint, "endpoint");
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        for (String key : settings.keySet()) {
            String k = key.toLowerCase(java.util.Locale.ROOT);
            if (k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("privatekey")) {
                throw new IllegalArgumentException("Secret-like setting '" + key + "' is not allowed; use a credential handle");
            }
        }
    }
}
