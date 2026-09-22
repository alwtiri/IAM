package com.enterprise.iam.providers.postgresql;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.testkit.ProviderContractTest;
import java.util.Map;
import java.util.UUID;

class PostgresProviderContractTest extends ProviderContractTest {

    @Override
    protected ProviderFactory factory() {
        return new PostgresProviderFactory();
    }

    @Override
    protected ProviderConnection connection() {
        return new ProviderConnection(UUID.randomUUID(), PostgresProvider.TYPE, "postgresql://db01.example.org:5432/app",
                Map.of("username", "iam_service"), new CredentialHandle("ch_contract"));
    }
}
