package com.enterprise.iam.core.provider.domain;

import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A configured connection to a provider (spec §11). Settings follow the SPI rules (no secret-like keys); the credential
 * is referenced by a Vault reference only. Health and circuit state are reported by worker pools from Phase 3.
 */
public record ProviderInstance(UUID id, ProviderTypeId type, String name, String endpoint, Map<String, String> settings,
                               String credentialSecretRef, boolean enabled, long version) {

    public ProviderInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (name == null || name.isBlank() || name.length() > 100) {
            throw IamException.validation("name", "INVALID", "required, max 100 characters");
        }
        if (endpoint == null || endpoint.length() > 500) {
            throw IamException.validation("endpoint", "INVALID", "required, max 500 characters");
        }
        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw IamException.validation("endpoint", "INVALID", "endpoint must be an absolute URI such as ssh://host:22 or https://vcenter");
        }
        settings = settings == null ? Map.of() : Map.copyOf(settings);
        if (settings.size() > 50) {
            throw IamException.validation("settings", "TOO_MANY", "max 50 settings");
        }
        try {
            // Reuse the SPI's own validation: rejects secret-like setting keys (password, secret, token, privatekey).
            new ProviderConnection(id, type, endpoint, settings, credentialSecretRef == null ? null : new CredentialHandle("ref"));
        } catch (IllegalArgumentException e) {
            throw IamException.validation("settings", "SECRET_NOT_ALLOWED", e.getMessage());
        }
    }

    public ProviderInstance withEnabled(boolean value) {
        return new ProviderInstance(id, type, name, endpoint, settings, credentialSecretRef, value, version);
    }
}
