package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CredentialResolver.SecretsUnavailableException;

/** Redeems a credential handle at the Core's internal API. */
@FunctionalInterface
public interface HandleRedeemer {
    Secret redeem(String handle) throws SecretsUnavailableException;
}
