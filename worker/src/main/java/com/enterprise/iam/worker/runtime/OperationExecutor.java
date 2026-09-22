package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.spi.ProviderOperation;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.ConnectionReport;
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import com.enterprise.iam.provider.spi.result.ProviderError;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes one operation command against a provider plugin and publishes exactly one final result (plus PROGRESS pages
 * for discovery). Isolation (G4): per-instance circuit breaker and bulkhead, a hard deadline, and fail-fast when a target
 * is known to be down. Honesty (G5): unsupported operations are reported as UNSUPPORTED, never simulated; a mutating
 * success needs the provider's verification.
 */
public final class OperationExecutor {

    private static final Logger LOG = Logger.getLogger(OperationExecutor.class.getName());
    /** Error codes that indicate the target (not the request) is unhealthy; they trip the breaker and allow retry. */
    static final Set<String> TARGET_FAILURES = Set.of("PROVIDER_UNAVAILABLE", "CONNECTION_FAILED", "OPERATION_TIMEOUT", "AUTHENTICATION_UNAVAILABLE");
    private static final int MAX_TRIES = 3;

    private final ProviderRegistry registry;
    private final InstanceGuards guards;
    private final HandleRedeemer redeemer;
    private final ResultSink sink;
    private final ExecutorService calls;
    private final Clock clock;
    private final String workerInstance;
    private final Duration retryBackoff;

    public OperationExecutor(ProviderRegistry registry, InstanceGuards guards, HandleRedeemer redeemer, ResultSink sink,
                             ExecutorService calls, Clock clock, String workerInstance, Duration retryBackoff) {
        this.registry = registry;
        this.guards = guards;
        this.redeemer = redeemer;
        this.sink = sink;
        this.calls = calls;
        this.clock = clock;
        this.workerInstance = workerInstance;
        this.retryBackoff = retryBackoff;
    }

    public void execute(Command c) {
        Instant start = clock.instant();
        if (!start.isBefore(c.deadline())) {
            publish(c, "TIMEOUT", null, error("OPERATION_TIMEOUT", "deadline passed before a worker picked up the operation", false, false), null, null);
            return;
        }
        ProviderOperation op;
        try {
            op = ProviderOperation.valueOf(c.operation());
        } catch (IllegalArgumentException e) {
            publish(c, "FAILED", null, error("CONTRACT_VIOLATION", "unknown operation " + c.operation(), false, false), null, null);
            return;
        }
        ProviderFactory factory = registry.find(c.providerType()).orElse(null);
        if (factory == null) {
            publish(c, "UNSUPPORTED", null, error(OperationResult.UNSUPPORTED_CAPABILITY,
                    "no provider implementation for type '" + c.providerType() + "' in this worker", false, false), null, null);
            return;
        }
        var capability = factory.descriptor().capability(op.requiredCapability());
        if (!capability.isSupported()) {
            publish(c, "UNSUPPORTED", null, error(OperationResult.UNSUPPORTED_CAPABILITY,
                    "capability " + op.requiredCapability() + " is " + capability.status() + ": " + capability.explanation(), false, false), null, null);
            return;
        }
        if (c.endpoint() == null) {
            publish(c, "FAILED", null, error("CONTRACT_VIOLATION", "command carries no connection endpoint", false, false), null, null);
            return;
        }
        InstanceGuards.Guard guard = guards.forInstance(c.providerInstanceId());
        try (OperationCredentials credentials = new OperationCredentials(redeemer)) {
            Outcome outcome = null;
            for (int attempt = 1; attempt <= MAX_TRIES; attempt++) {
                if (!guard.breaker().tryAcquire()) {
                    outcome = Outcome.of(error("PROVIDER_UNAVAILABLE", "circuit open for provider instance " + c.providerInstanceId()
                            + " after repeated connection failures; failing fast", true, false));
                    break;
                }
                outcome = runGuarded(c, op, factory, guard, credentials);
                ErrorView err = outcome.errorView();
                boolean targetFailure = err != null && TARGET_FAILURES.contains(err.code());
                if (targetFailure) {
                    guard.breaker().onFailure();
                } else {
                    guard.breaker().onSuccess();
                }
                boolean retry = err != null && err.retryable() && !err.changeMayHaveApplied()
                        && attempt < MAX_TRIES && clock.instant().plus(retryBackoff.multipliedBy(attempt)).isBefore(c.deadline());
                if (!retry) {
                    break;
                }
                sleep(retryBackoff.multipliedBy(attempt));
            }
            publish(c, outcome.outcome(), outcome.verification(), outcome.error(), outcome.payload(), null);
        }
    }

    // ------------------------------------------------------------------ one guarded provider call

    private record Outcome(String outcome, Map<String, Object> verification, Map<String, Object> error, Map<String, Object> payload) {
        static Outcome of(Map<String, Object> error) {
            return new Outcome("FAILED", null, error, null);
        }

        ErrorView errorView() {
            return error == null ? null : new ErrorView(String.valueOf(error.get("code")), Boolean.TRUE.equals(error.get("retryable")),
                    Boolean.TRUE.equals(error.get("changeMayHaveApplied")));
        }
    }

    private record ErrorView(String code, boolean retryable, boolean changeMayHaveApplied) {
    }

    private Outcome runGuarded(Command c, ProviderOperation op, ProviderFactory factory, InstanceGuards.Guard guard, OperationCredentials creds) {
        Duration remaining = Duration.between(clock.instant(), c.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            return new Outcome("TIMEOUT", null, error("OPERATION_TIMEOUT", "deadline reached", true, op.mutating()), null);
        }
        boolean permit = false;
        try {
            permit = guard.bulkhead().tryAcquire(Math.min(remaining.toMillis(), 30_000), TimeUnit.MILLISECONDS);
            if (!permit) {
                return Outcome.of(error("PROVIDER_BUSY", "all connections to this provider instance are busy", true, false));
            }
            Future<Outcome> call = calls.submit(() -> invoke(c, op, factory, creds));
            try {
                return call.get(Math.max(1, Duration.between(clock.instant(), c.deadline()).toMillis()), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                call.cancel(true);
                return new Outcome("TIMEOUT", null, error("OPERATION_TIMEOUT", "provider call exceeded the operation deadline", true,
                        op.mutating()), null);
            } catch (ExecutionException e) {
                LOG.log(Level.WARNING, "Provider " + c.providerType() + " threw " + e.getCause().getClass().getName()
                        + " for operation " + c.operationId());
                return Outcome.of(error("PROVIDER_ERROR", "provider failed: " + e.getCause().getClass().getSimpleName(), false,
                        op.mutating()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome("UNKNOWN", null, error("OUTCOME_UNVERIFIED", "worker interrupted", false, op.mutating()), null);
        } finally {
            if (permit) {
                guard.bulkhead().release();
            }
        }
    }

    private Outcome invoke(Command c, ProviderOperation op, ProviderFactory factory, OperationCredentials creds) {
        String connectionHandle = c.credentialHandles().get("connection");
        ProviderConnection connection = new ProviderConnection(c.providerInstanceId(), ProviderTypeId.of(c.providerType()), c.endpoint(),
                c.settings(), connectionHandle == null ? null : new CredentialHandle(connectionHandle));
        Provider provider = factory.create(connection);
        OperationContext ctx = new OperationContext(c.operationId(), c.idempotencyKey(), c.correlationId(), c.attempt(), c.deadline(), creds);
        try {
            return switch (op) {
                case VALIDATE_CONNECTION -> single(provider.validateConnection(ctx), r -> connectionPayload(r));
                case DISCOVER_ACCOUNTS -> discoverAccounts(c, provider, ctx);
                case GET_ACCOUNT_STATE -> single(provider.getAccountState(ctx, account(c)), s -> Map.of("state", AccountJson.state(s)));
                case ENABLE_ACCOUNT -> single(provider.enableAccount(ctx, account(c)), s -> Map.of("state", AccountJson.state(s)));
                case DISABLE_ACCOUNT -> single(provider.disableAccount(ctx, account(c)), s -> Map.of("state", AccountJson.state(s)));
                case UNLOCK_ACCOUNT -> single(provider.unlockAccount(ctx, account(c)), s -> Map.of("state", AccountJson.state(s)));
                default -> new Outcome("UNSUPPORTED", null, error(OperationResult.UNSUPPORTED_CAPABILITY,
                        "operation " + op + " is not executed by the Phase 3 worker", false, false), null);
            };
        } finally {
            if (provider instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    LOG.fine("provider close failed: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private Outcome discoverAccounts(Command c, Provider provider, OperationContext ctx) {
        String cursor = null;
        int sequence = 0;
        int total = 0;
        do {
            if (!clock.instant().isBefore(c.deadline())) {
                return new Outcome("PARTIAL", null, error("OPERATION_TIMEOUT", "deadline reached after " + sequence + " page(s)", false, false), null);
            }
            OperationResult<Page<AccountState>> r = provider.discoverAccounts(ctx, cursor);
            if (!r.isSuccess()) {
                Outcome failed = single(r, p -> Map.of());
                return sequence == 0 ? failed : new Outcome("PARTIAL", null, failed.error(), null);
            }
            Page<AccountState> page = r.value().orElse(new Page<>(List.of(), null));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("accounts", AccountJson.states(page.items()));
            publish(c, "PROGRESS", null, null, payload, sequence++);
            total += page.items().size();
            cursor = page.nextCursor();
        } while (cursor != null);
        return new Outcome("SUCCEEDED", null, null, Map.of("pages", sequence, "accounts", List.of(), "total", total));
    }

    private static <T> Outcome single(OperationResult<T> r, java.util.function.Function<T, Map<String, Object>> payload) {
        Map<String, Object> verification = r.verification().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", v.mode().name());
            m.put("verifiedAt", v.verifiedAt().toString());
            m.put("summary", v.summary());
            return m;
        }).orElse(null);
        Map<String, Object> err = r.error().map(OperationExecutor::error).orElse(null);
        Map<String, Object> data = r.isSuccess() ? r.value().map(payload).orElse(Map.of()) : null;
        return new Outcome(outcomeName(r.outcome()), verification, err, data);
    }

    private static String outcomeName(OperationOutcome o) {
        return o.name();
    }

    private static Map<String, Object> connectionPayload(ConnectionReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connection", String.valueOf(r));
        return m;
    }

    private static AccountRef account(Command c) {
        if (!(c.payload().get("account") instanceof Map<?, ?> a) || a.get("name") == null) {
            throw new IllegalArgumentException("command payload has no account reference");
        }
        return new AccountRef(a.get("nativeId") == null ? null : a.get("nativeId").toString(), a.get("name").toString());
    }

    // ------------------------------------------------------------------ result messages

    private void publish(Command c, String outcome, Map<String, Object> verification, Map<String, Object> error, Map<String, Object> payload,
                         Integer sequence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("messageId", UUID.randomUUID().toString());
        m.put("operationId", c.operationId().toString());
        m.put("attempt", c.attempt());
        m.put("outcome", outcome);
        if (verification != null) {
            m.put("verification", verification);
        }
        if (!"SUCCEEDED".equals(outcome) && !"PROGRESS".equals(outcome)) {
            m.put("error", error != null ? error : error("PROVIDER_ERROR", "no error detail", false, false));
        }
        if (payload != null) {
            m.put("resultPayload", payload);
        }
        if (sequence != null) {
            m.put("sequence", sequence);
        }
        m.put("workerInstance", workerInstance);
        m.put("correlationId", c.correlationId());
        m.put("completedAt", clock.instant().toString());
        sink.publish(m);
    }

    static Map<String, Object> error(ProviderError e) {
        return error(e.code(), e.message(), e.retryable(), e.changeMayHaveApplied());
    }

    static Map<String, Object> error(String code, String message, boolean retryable, boolean changeMayHaveApplied) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message == null ? "" : message.length() > 1000 ? message.substring(0, 1000) : message);
        m.put("retryable", retryable);
        m.put("changeMayHaveApplied", changeMayHaveApplied);
        return m;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
