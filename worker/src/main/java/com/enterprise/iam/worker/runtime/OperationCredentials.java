package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.CredentialResolver;
import java.util.ArrayList;
import java.util.List;

/**
 * Credential resolver scoped to ONE operation execution. Handles are single-use at the Core, so a handle redeemed once is
 * kept in memory only until the execution ends (retries inside the same execution reuse it) and is then destroyed.
 * Nothing survives the operation (G3: no plaintext cache).
 */
public final class OperationCredentials implements CredentialResolver, AutoCloseable {

    private record Redeemed(String handle, Secret secret) {
    }

    private final HandleRedeemer redeemer;
    private final List<Redeemed> redeemed = new ArrayList<>();

    public OperationCredentials(HandleRedeemer redeemer) {
        this.redeemer = redeemer;
    }

    @Override
    public synchronized Secret redeem(CredentialHandle handle) throws SecretsUnavailableException {
        for (Redeemed r : redeemed) {
            if (r.handle().equals(handle.value())) {
                return r.secret().copy();
            }
        }
        Secret s = redeemer.redeem(handle.value());
        redeemed.add(new Redeemed(handle.value(), s));
        return s.copy();
    }

    @Override
    public synchronized void close() {
        redeemed.forEach(r -> r.secret().destroy());
        redeemed.clear();
    }
}
