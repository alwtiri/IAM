package com.enterprise.iam.provider.spi;

import java.util.Objects;

/**
 * Opaque, single-use, short-lived reference to a secret held in Vault (ADR-0005, SECURITY-ARCHITECTURE §5).
 * Contains no secret material; safe to log by value.
 */
public record CredentialHandle(String value) {

    public CredentialHandle {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Invalid credential handle");
        }
    }
}
