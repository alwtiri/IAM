package com.enterprise.iam.providers.windows;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.testkit.ProviderContractTest;
import java.util.Map;
import java.util.UUID;

class WindowsProviderContractTest extends ProviderContractTest {

    @Override
    protected ProviderFactory factory() {
        return new WindowsProviderFactory();
    }

    @Override
    protected ProviderConnection connection() {
        return new ProviderConnection(UUID.randomUUID(), WindowsProvider.TYPE, "https://win01.example.org:5986/wsman",
                Map.of("username", "svc-iam"), new CredentialHandle("ch_contract"));
    }
}
