package com.enterprise.iam.provider.spi;

import com.enterprise.iam.kernel.Secret;

/**
 * Redeems credential handles just-in-time. Implemented by the worker runtime (mTLS call to the Core
 * secrets broker). Providers must {@link Secret#destroy()} the returned secret as soon as it is no longer needed.
 */
@FunctionalInterface
public interface CredentialResolver {

    /**
     * @throws SecretsUnavailableException if the secret store cannot provide the value (fail closed)
     */
    Secret redeem(CredentialHandle handle) throws SecretsUnavailableException;

    /** Signals that secret material could not be obtained; the operation must fail closed. */
    final class SecretsUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        public SecretsUnavailableException(String message) {
            super(message);
        }
    }
}
