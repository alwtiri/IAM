package com.enterprise.iam.provider.testkit;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.testkit.fixture.InMemoryTestProvider;
import com.enterprise.iam.provider.testkit.fixture.InMemoryTestProviderFactory;
import java.util.Map;
import java.util.UUID;

/** Self-test of the contract kit against a compliant fixture. */
class InMemoryTestProviderContractTest extends ProviderContractTest {

    @Override
    protected ProviderFactory factory() {
        return new InMemoryTestProviderFactory();
    }

    @Override
    protected ProviderConnection connection() {
        return new ProviderConnection(UUID.randomUUID(), InMemoryTestProvider.TYPE, "memory://fixture",
                Map.of("region", "test"), new CredentialHandle("fixture-handle"));
    }
}
