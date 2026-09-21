package com.enterprise.iam.provider.testkit.fixture;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;

/** TEST FIXTURE ONLY. */
public final class InMemoryTestProviderFactory implements ProviderFactory {
    @Override
    public ProviderDescriptor descriptor() {
        return InMemoryTestProvider.DESCRIPTOR;
    }

    @Override
    public Provider create(ProviderConnection connection) {
        return new InMemoryTestProvider();
    }
}
