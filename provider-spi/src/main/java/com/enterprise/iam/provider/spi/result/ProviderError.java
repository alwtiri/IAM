package com.enterprise.iam.provider.spi.result;

import java.util.Objects;

/**
 * Failure details returned by a provider. {@code message} must not contain secret values.
 *
 * @param code                 stable machine-readable code, e.g. {@code CONNECTION_REFUSED}, {@code AUTH_FAILED},
 *                             {@code ACCOUNT_NOT_FOUND}, {@code UNSUPPORTED_CAPABILITY}, {@code SECRETS_UNAVAILABLE}
 * @param message              safe human-readable explanation
 * @param retryable            whether retrying the same operation may succeed
 * @param changeMayHaveApplied true if the target may have been modified despite the failure
 */
public record ProviderError(String code, String message, boolean retryable, boolean changeMayHaveApplied) {

    public ProviderError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
