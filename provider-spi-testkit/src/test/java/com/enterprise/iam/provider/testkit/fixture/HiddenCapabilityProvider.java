package com.enterprise.iam.provider.testkit.fixture;

import com.enterprise.iam.provider.spi.Capability;
import com.enterprise.iam.provider.spi.CapabilityDescriptor;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderOperation;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import com.enterprise.iam.provider.spi.VerificationMode;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.result.OperationResult;

/**
 * TEST FIXTURE ONLY — deliberately non-compliant: declares ACCOUNT_DISABLE without implementing it and
 * implements ACCOUNT_UNLOCK without declaring it. The contract kit must report both.
 */
public final class HiddenCapabilityProvider implements Provider {

    @Override
    public ProviderDescriptor descriptor() {
        return ProviderDescriptor.builder(ProviderTypeId.of("broken-fixture"), "0.0.0-test")
                .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISABLE, VerificationMode.READ_BACK))
                .build();
    }

    @Override
    public OperationResult<AccountState> unlockAccount(OperationContext ctx, AccountRef account) {
        return OperationResult.unknown(ProviderOperation.UNLOCK_ACCOUNT, "fixture");
    }
}
