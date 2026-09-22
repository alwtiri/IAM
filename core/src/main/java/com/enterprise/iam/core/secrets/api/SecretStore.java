package com.enterprise.iam.core.secrets.api;

import com.enterprise.iam.kernel.Secret;

/**
 * The only way Core modules store secret material (ADR-0005, G3). Every method fails with
 * {@code IamException(SECRETS_UNAVAILABLE)} when Vault cannot be reached; there is no plaintext fallback.
 */
public interface SecretStore {

    /**
     * Writes a new version of the secret at a logical path such as {@code providers/<id>/connection}.
     * The secret is not destroyed by this call; the caller owns it.
     */
    SecretRef write(String logicalPath, Secret value);

    /** Reads the exact version a reference points to. Callers must destroy the returned secret after use. */
    Secret read(SecretRef ref);

    /** Permanently destroys all versions at the reference's path (compensation / decommissioning). */
    void destroy(SecretRef ref);
}
