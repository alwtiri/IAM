package com.enterprise.iam.providers.ad;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.testkit.ProviderContractTest;
import java.util.Map;
import java.util.UUID;

class ActiveDirectoryProviderContractTest extends ProviderContractTest {

    @Override
    protected ProviderFactory factory() {
        return new ActiveDirectoryProviderFactory();
    }

    @Override
    protected ProviderConnection connection() {
        return new ProviderConnection(UUID.randomUUID(), ActiveDirectoryProvider.TYPE, "ldaps://dc1.example.org",
                Map.of("bindDn", "svc-iam@example.org", "baseDn", "DC=example,DC=org"), new CredentialHandle("ch_contract"));
    }
}
