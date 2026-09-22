package com.enterprise.iam.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.Capability;
import com.enterprise.iam.provider.spi.CapabilityDescriptor;
import com.enterprise.iam.provider.spi.CredentialResolver;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.spi.ProviderOperation;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import com.enterprise.iam.provider.spi.VerificationMode;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.NativeAccountStatus;
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.result.OperationResult;
import com.enterprise.iam.provider.spi.result.ProviderError;
import com.enterprise.iam.provider.spi.result.Verification;
import com.enterprise.iam.worker.runtime.CircuitBreaker;
import com.enterprise.iam.worker.runtime.Command;
import com.enterprise.iam.worker.runtime.InstanceGuards;
import com.enterprise.iam.worker.runtime.OperationExecutor;
import com.enterprise.iam.worker.runtime.ProviderRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Worker isolation and honesty rules (WORKER-ISOLATION §3, §8; G4, G5). */
class OperationExecutorTest {

    static final ProviderTypeId TYPE = ProviderTypeId.of("linux-ssh");

    /** Scriptable fake target. */
    static final class FakeTarget implements ProviderFactory {
        volatile boolean down;
        volatile long delayMillis;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> redeemed = new CopyOnWriteArrayList<>();
        final ProviderDescriptor descriptor = ProviderDescriptor.builder(TYPE, "1.0-test")
                .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISCOVERY, null))
                .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_STATE_READ, null))
                .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISABLE, VerificationMode.READ_BACK))
                .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_UNLOCK, "no lockout mechanism on this platform"))
                .build();

        @Override
        public ProviderDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Provider create(ProviderConnection connection) {
            return new Provider() {
                @Override
                public ProviderDescriptor descriptor() {
                    return descriptor;
                }

                private OperationResult<AccountState> connect(OperationContext ctx, ProviderOperation op) {
                    calls.incrementAndGet();
                    try (Secret s = ctx.credentials().redeem(connection.credential())) {
                        redeemed.add(new String(s.reveal()));
                    } catch (CredentialResolver.SecretsUnavailableException e) {
                        return OperationResult.secretsUnavailable(op);
                    }
                    if (delayMillis > 0) {
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (down) {
                        return OperationResult.failed(op, new ProviderError("CONNECTION_FAILED", "connection refused", true, false));
                    }
                    return null;
                }

                @Override
                public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
                    OperationResult<AccountState> failed = connect(ctx, ProviderOperation.DISABLE_ACCOUNT);
                    if (failed != null) {
                        return failed;
                    }
                    AccountState s = new AccountState(new AccountRef("uid:1001", account.name()), NativeAccountStatus.DISABLED, false, List.of(),
                            null, null, null, Map.of("shell", "/sbin/nologin"));
                    return OperationResult.succeeded(ProviderOperation.DISABLE_ACCOUNT, s,
                            new Verification(VerificationMode.READ_BACK, Instant.now(), "account reads back as locked"));
                }

                @Override
                public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
                    if (cursor == null) {
                        OperationResult<AccountState> failed = connect(ctx, ProviderOperation.DISCOVER_ACCOUNTS);
                        if (failed != null) {
                            return OperationResult.failed(ProviderOperation.DISCOVER_ACCOUNTS, failed.error().orElseThrow());
                        }
                    }
                    int page = cursor == null ? 0 : Integer.parseInt(cursor);
                    List<AccountState> items = new ArrayList<>();
                    for (int i = 0; i < 2; i++) {
                        items.add(new AccountState(new AccountRef("uid:" + page + i, "user" + page + i), NativeAccountStatus.ENABLED, false,
                                List.of(), null, null, null, Map.of()));
                    }
                    return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(items, page < 2 ? String.valueOf(page + 1) : null));
                }
            };
        }
    }

    FakeTarget target;
    List<Map<String, Object>> results;
    AtomicInteger redemptions;
    InstanceGuards guards;
    OperationExecutor executor;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        target = new FakeTarget();
        results = new CopyOnWriteArrayList<>();
        redemptions = new AtomicInteger();
        pool = Executors.newVirtualThreadPerTaskExecutor();
        guards = new InstanceGuards(() -> new CircuitBreaker(20, 0.5, 3, Duration.ofSeconds(60), Clock.systemUTC()), 4);
        executor = new OperationExecutor(new ProviderRegistry(List.of(target), List.of("linux-ssh")), guards, handle -> {
            redemptions.incrementAndGet();
            return Secret.of("pw-for-" + handle);
        }, results::add, pool, Clock.systemUTC(), "worker-test", Duration.ofMillis(10));
    }

    static Command command(String op, UUID instance, Duration timeout, String type) {
        return new Command(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(), type, instance, UUID.randomUUID(), op, 1,
                Instant.now().plus(timeout), "corr-12345678", Map.of("connection", "ch_test"),
                Map.of("account", Map.of("nativeId", "uid:1001", "name", "alice"),
                        "connection", Map.of("endpoint", "ssh://srv01:22", "settings", Map.of("sudo", "true"))));
    }

    static Command command(String op, UUID instance) {
        return command(op, instance, Duration.ofSeconds(30), "linux-ssh");
    }

    Map<String, Object> last() {
        return results.get(results.size() - 1);
    }

    @Test
    void verifiedDisableSucceedsWithStateAndVerification() {
        executor.execute(command("DISABLE_ACCOUNT", UUID.randomUUID()));
        Map<String, Object> r = last();
        assertEquals("SUCCEEDED", r.get("outcome"));
        assertNotNull(r.get("verification"));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) ((Map<String, Object>) r.get("resultPayload")).get("state");
        assertEquals("DISABLED", state.get("status"));
        assertEquals(List.of("pw-for-ch_test"), target.redeemed);
    }

    @Test
    void unsupportedCapabilityAndUnknownTypeAreReportedHonestly() {
        executor.execute(command("UNLOCK_ACCOUNT", UUID.randomUUID()));
        assertEquals("UNSUPPORTED", last().get("outcome"));
        assertTrue(String.valueOf(((Map<?, ?>) last().get("error")).get("message")).contains("no lockout mechanism"));
        executor.execute(command("DISABLE_ACCOUNT", UUID.randomUUID(), Duration.ofSeconds(30), "vmware"));
        assertEquals("UNSUPPORTED", last().get("outcome"));
        assertEquals(0, target.calls.get(), "nothing was simulated");
    }

    @Test
    void discoveryPublishesOneProgressMessagePerPage() {
        executor.execute(command("DISCOVER_ACCOUNTS", UUID.randomUUID()));
        assertEquals(4, results.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("PROGRESS", results.get(i).get("outcome"));
            assertEquals(i, results.get(i).get("sequence"));
        }
        assertEquals("SUCCEEDED", last().get("outcome"));
        assertEquals(6, ((Map<?, ?>) last().get("resultPayload")).get("total"));
    }

    @Test
    void expiredCommandTimesOutWithoutCallingTheTarget() {
        executor.execute(command("DISABLE_ACCOUNT", UUID.randomUUID(), Duration.ofSeconds(-1), "linux-ssh"));
        assertEquals("TIMEOUT", last().get("outcome"));
        assertEquals(0, target.calls.get());
    }

    @Test
    void transientConnectionFailureIsRetriedWithTheSameRedeemedHandle() {
        target.down = true;
        executor.execute(command("DISABLE_ACCOUNT", UUID.randomUUID()));
        assertEquals("FAILED", last().get("outcome"));
        assertEquals(3, target.calls.get(), "3 tries within the deadline");
        assertEquals(1, redemptions.get(), "a single-use handle is redeemed once per execution");
    }

    @Test
    void slowTargetTimesOutAtTheDeadlineAndMarksTheChangeAsPossiblyApplied() {
        target.delayMillis = 2_000;
        long start = System.nanoTime();
        executor.execute(command("DISABLE_ACCOUNT", UUID.randomUUID(), Duration.ofMillis(300), "linux-ssh"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1_500, "deadline enforced");
        assertEquals("TIMEOUT", last().get("outcome"));
        assertEquals(true, ((Map<?, ?>) last().get("error")).get("changeMayHaveApplied"));
    }

    @Test
    void openBreakerOnOneInstanceFailsFastWithoutAffectingAnother() {
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        target.down = true;
        executor.execute(command("DISABLE_ACCOUNT", broken)); // 3 consecutive connection failures -> open
        assertEquals(CircuitBreaker.State.OPEN, guards.forInstance(broken).breaker().state());
        int callsBefore = target.calls.get();
        executor.execute(command("DISABLE_ACCOUNT", broken));
        assertEquals(callsBefore, target.calls.get(), "fail fast: the dead target is not called");
        assertTrue(String.valueOf(((Map<?, ?>) last().get("error")).get("message")).contains("circuit open"));

        target.down = false;
        executor.execute(command("DISABLE_ACCOUNT", healthy));
        assertEquals("SUCCEEDED", last().get("outcome"), "another instance of the same type is unaffected");
    }

    @Test
    void commandParsesTheContractShape() {
        Command c = Command.parse("""
                {"schemaVersion":1,"messageId":"%s","operationId":"%s","idempotencyKey":"disable:a:1","providerType":"linux-ssh",
                 "providerInstanceId":"%s","operation":"DISABLE_ACCOUNT","attempt":2,"deadline":"2030-01-01T00:00:00Z",
                 "correlationId":"corr-12345678","credentialHandles":{"connection":"ch_x"},
                 "payload":{"account":{"name":"alice"},"connection":{"endpoint":"ssh://h:22","settings":{"sudo":"true"}}}}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(2, c.attempt());
        assertEquals("ssh://h:22", c.endpoint());
        assertEquals(Map.of("sudo", "true"), c.settings());
        assertEquals("ch_x", c.credentialHandles().get("connection"));
    }
}
