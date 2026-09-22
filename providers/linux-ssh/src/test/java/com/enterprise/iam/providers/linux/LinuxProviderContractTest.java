package com.enterprise.iam.providers.linux;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.testkit.ProviderContractTest;
import java.util.Map;
import java.util.UUID;

class LinuxProviderContractTest extends ProviderContractTest {

    @Override
    protected ProviderFactory factory() {
        return new LinuxProviderFactory();
    }

    @Override
    protected ProviderConnection connection() {
        return new ProviderConnection(UUID.randomUUID(), LinuxProvider.TYPE, "ssh://srv01.example.org:22",
                Map.of("username", "svc-iam", "hostKeyFingerprint", "SHA256:abc"), new CredentialHandle("ch_contract"));
    }
}
