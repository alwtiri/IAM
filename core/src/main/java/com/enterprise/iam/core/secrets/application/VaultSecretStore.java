package com.enterprise.iam.core.secrets.application;

import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.kernel.Secret;

/** {@link SecretStore} on Vault KV v2. */
public class VaultSecretStore implements SecretStore {

    private final VaultClient vault;

    public VaultSecretStore(VaultClient vault) {
        this.vault = vault;
    }

    @Override
    public SecretRef write(String logicalPath, Secret value) {
        long version = vault.writeKv(logicalPath, value);
        return SecretRef.of(vault.kvMount(), logicalPath, version);
    }

    @Override
    public Secret read(SecretRef ref) {
        return vault.readKv(ref.path(), ref.version());
    }

    @Override
    public void destroy(SecretRef ref) {
        vault.destroyKv(ref.path());
    }
}
