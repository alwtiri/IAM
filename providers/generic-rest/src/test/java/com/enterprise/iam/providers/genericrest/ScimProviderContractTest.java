package com.enterprise.iam.providers.genericrest;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.testkit.ProviderContractTest;
import java.util.Map;
import java.util.UUID;

class ScimProviderContractTest extends ProviderContractTest {

    @Override
    protected ProviderFactory factory() {
        return new ScimProviderFactory();
    }

    @Override
    protected ProviderConnection connection() {
        return new ProviderConnection(UUID.randomUUID(), ScimProvider.TYPE, "https://app.example.org/scim/v2", Map.of(),
                new CredentialHandle("ch_contract"));
    }
}
