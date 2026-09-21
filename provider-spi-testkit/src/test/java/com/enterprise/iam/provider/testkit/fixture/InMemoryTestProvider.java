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
import com.enterprise.iam.provider.spi.model.AccountSpec;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.ConnectionReport;
import com.enterprise.iam.provider.spi.model.DiscoverySummary;
import com.enterprise.iam.provider.spi.model.NativeAccountStatus;
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.result.OperationResult;
import com.enterprise.iam.provider.spi.result.ProviderError;
import com.enterprise.iam.provider.spi.result.Verification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEST FIXTURE ONLY (gate clarification G10) — lives in test sources, never packaged into runtime images.
 * Simulates a target in memory to exercise the contract kit: idempotent create, verified disable, honest
 * UNSUPPORTED for everything else.
 */
public final class InMemoryTestProvider implements Provider {

    public static final ProviderTypeId TYPE = ProviderTypeId.of("test-fixture");

    static final ProviderDescriptor DESCRIPTOR = ProviderDescriptor.builder(TYPE, "0.0.0-test")
            .capability(CapabilityDescriptor.supported(Capability.CONNECTION_VALIDATION, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISCOVERY, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_STATE_READ, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_CREATE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.unsupported(Capability.PASSWORD_ROTATION, "Fixture has no credentials"))
            .capability(CapabilityDescriptor.agentRequired(Capability.AUDIT, "Fixture: telemetry would need an agent"))
            .build();

    private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        return OperationResult.read(ProviderOperation.VALIDATE_CONNECTION, new ConnectionReport("in-memory", "1", null));
    }

    @Override
    public OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        return OperationResult.read(ProviderOperation.DISCOVER, new DiscoverySummary(accounts.size(), 0, 0));
    }

    @Override
    public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(new ArrayList<>(accounts.values()), null));
    }

    @Override
    public OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        AccountState s = accounts.get(account.name());
        return s == null
                ? OperationResult.failed(ProviderOperation.GET_ACCOUNT_STATE,
                        new ProviderError("ACCOUNT_NOT_FOUND", "No such account", false, false))
                : OperationResult.read(ProviderOperation.GET_ACCOUNT_STATE, s);
    }

    @Override
    public OperationResult<AccountState> createAccount(OperationContext ctx, AccountSpec spec) {
        accounts.computeIfAbsent(spec.name(), n -> new AccountState(new AccountRef(UUID.randomUUID().toString(), n),
                NativeAccountStatus.ENABLED, false, List.of(), null, null, null, spec.attributes()));
        return readBack(ProviderOperation.CREATE_ACCOUNT, spec.name(), NativeAccountStatus.ENABLED);
    }

    @Override
    public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        AccountState s = accounts.get(account.name());
        if (s == null) {
            return OperationResult.failed(ProviderOperation.DISABLE_ACCOUNT,
                    new ProviderError("ACCOUNT_NOT_FOUND", "No such account", false, false));
        }
        accounts.put(account.name(), new AccountState(s.account(), NativeAccountStatus.DISABLED, s.privileged(),
                s.groups(), s.lastLogin(), s.passwordLastSet(), s.passwordExpires(), s.attributes()));
        return readBack(ProviderOperation.DISABLE_ACCOUNT, account.name(), NativeAccountStatus.DISABLED);
    }

    @Override
    public OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        return getAccountState(ctx, account).isSuccess()
                ? OperationResult.read(ProviderOperation.VERIFY_OPERATION, accounts.get(account.name()))
                : OperationResult.unknown(ProviderOperation.VERIFY_OPERATION, "Account not observable");
    }

    private OperationResult<AccountState> readBack(ProviderOperation op, String name, NativeAccountStatus expected) {
        AccountState observed = accounts.get(name);
        if (observed == null || observed.status() != expected) {
            return OperationResult.unknown(op, "Read-back did not confirm expected state " + expected);
        }
        return OperationResult.succeeded(op, observed,
                new Verification(VerificationMode.READ_BACK, Instant.now(), "status=" + observed.status()));
    }
}
