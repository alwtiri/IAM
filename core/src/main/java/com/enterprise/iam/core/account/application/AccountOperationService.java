package com.enterprise.iam.core.account.application;

import com.enterprise.iam.core.account.api.DiscoveryRunView;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.operation.api.OperationCommands;
import com.enterprise.iam.core.operation.api.OperationCompleted;
import com.enterprise.iam.core.operation.api.OperationProgress;
import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.provider.api.ProviderDirectory;
import com.enterprise.iam.core.secrets.api.CredentialHandles;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.target.api.TargetDirectory;
import com.enterprise.iam.kernel.IamException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns account intents into provider operations on the worker plane and applies their results (PHASE-3-DESIGN §3):
 * discovery runs and lifecycle operations (enable, disable, unlock, state read).
 *
 * <p>Everything a request needs (operation row, discovery run, credential handles, audit, outbox command) is written in
 * one transaction. The credential itself never leaves Vault here: the command carries a single-use handle.
 */
public class AccountOperationService {

    /** Lifecycle actions exposed through the API in Phase 3; password operations follow with credential management. */
    public enum Action {
        ENABLE("ENABLE_ACCOUNT", true), DISABLE("DISABLE_ACCOUNT", true), UNLOCK("UNLOCK_ACCOUNT", true), REFRESH("GET_ACCOUNT_STATE", false);

        final String operation;
        final boolean mutating;

        Action(String operation, boolean mutating) {
            this.operation = operation;
            this.mutating = mutating;
        }
    }

    public record Submitted(UUID operationId, UUID discoveryRunId) {
    }

    static final Set<String> ACCOUNT_OPERATIONS = Set.of("ENABLE_ACCOUNT", "DISABLE_ACCOUNT", "UNLOCK_ACCOUNT", "GET_ACCOUNT_STATE");
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration LIFECYCLE_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration HANDLE_TTL = Duration.ofMinutes(10);

    private final AccountService accounts;
    private final AccountStore store;
    private final OperationCommands operations;
    private final CredentialHandles handles;
    private final ProviderDirectory providers;
    private final TargetDirectory targets;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;

    public AccountOperationService(AccountService accounts, AccountStore store, OperationCommands operations, CredentialHandles handles,
                                   ProviderDirectory providers, TargetDirectory targets, AccessGuard guard, AuditRecorder audit,
                                   TransactionRunner tx) {
        this.accounts = accounts;
        this.store = store;
        this.operations = operations;
        this.handles = handles;
        this.providers = providers;
        this.targets = targets;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ requests

    public Submitted requestDiscovery(CurrentActor actor, UUID targetId, UUID providerInstanceId) {
        return tx.inTransaction(() -> {
            ProviderDirectory.Connection c = connection(providerInstanceId);
            ResourceScope targetScope = targets.scopeOf(targetId).orElseThrow(() -> IamException.notFound("Target"));
            ResourceScope scope = new ResourceScope(targetScope.orgUnitPath(), targetScope.environment(), c.type(), c.id(), targetId);
            guard.require(actor, Permissions.ACCOUNT_DISCOVER, scope, true);
            if (!providers.isBound(targetId, providerInstanceId)) {
                throw IamException.validation("providerInstanceId", "NOT_BOUND", "bind the provider instance to the target first");
            }
            ProviderCommand cmd = new ProviderCommand("DISCOVER_ACCOUNTS", false, c.type(), c.id(), targetId, null, actor.identityId(),
                    targetScope.orgUnitPath(), targetScope.environment(), Map.of("includeGroups", true), DISCOVERY_TIMEOUT, 1,
                    "discover:" + targetId + ":" + UUID.randomUUID());
            UUID opId = operations.create(cmd);
            UUID runId = accounts.startDiscoveryRun(targetId, providerInstanceId, opId);
            operations.dispatch(opId, cmd, handles.issue(opId, c.type(), Map.of("connection", new SecretRef(c.credentialSecretRef())), HANDLE_TTL));
            audit.record(actor, new AuditEntry("account.discovery-requested", "target", targetId.toString(), targetId,
                    AuditEntry.Result.SUCCESS, null, providerInstanceId, Map.of("operation", opId.toString(), "run", runId.toString())));
            return new Submitted(opId, runId);
        });
    }

    public Submitted requestLifecycle(CurrentActor actor, UUID accountId, Action action, String reason) {
        return tx.inTransaction(() -> {
            AccountStore.Scoped s = store.find(accountId).orElseThrow(() -> IamException.notFound("Account"));
            guard.require(actor, action.mutating ? Permissions.OPERATION_EXECUTE : Permissions.ACCOUNT_READ, AccountService.scope(s), true);
            Account a = s.account();
            if (a.nativeStatus() == Account.NativeStatus.ABSENT) {
                throw IamException.invalidTransition("Account", "ABSENT", action.name());
            }
            ProviderDirectory.Connection c = connection(a.providerInstanceId());
            ProviderCommand cmd = new ProviderCommand(action.operation, action.mutating, c.type(), c.id(), a.targetId(), a.id(),
                    actor.identityId(), s.orgUnitPath(), s.environment(),
                    Map.of("account", Map.of("nativeId", a.nativeId(), "name", a.name())), LIFECYCLE_TIMEOUT, 1,
                    action.operation.toLowerCase(java.util.Locale.ROOT) + ":" + a.id() + ":" + a.version() + ":" + UUID.randomUUID());
            UUID opId = operations.create(cmd);
            operations.dispatch(opId, cmd, handles.issue(opId, c.type(), Map.of("connection", new SecretRef(c.credentialSecretRef())), HANDLE_TTL));
            if (action.mutating) {
                audit.record(actor, new AuditEntry("account.operation-requested", "account", a.id().toString(), a.targetId(),
                        AuditEntry.Result.SUCCESS, reason, a.providerInstanceId(), Map.of("operation", action.operation, "id", opId.toString())));
            }
            return new Submitted(opId, null);
        });
    }

    private ProviderDirectory.Connection connection(UUID providerInstanceId) {
        ProviderDirectory.Connection c = providers.connection(providerInstanceId).orElseThrow(() -> IamException.notFound("Provider instance"));
        if (!c.enabled()) {
            throw IamException.validation("providerInstanceId", "DISABLED", "provider instance is disabled");
        }
        if (c.credentialSecretRef() == null) {
            throw IamException.validation("providerInstanceId", "NO_CREDENTIAL", "provider instance has no connection credential");
        }
        return c;
    }

    // ------------------------------------------------------------------ results (called by event listeners, same transaction)

    public void onProgress(OperationProgress p) {
        if (!"DISCOVER_ACCOUNTS".equals(p.operation())) {
            return;
        }
        store.findRunByOperation(p.operationId()).ifPresent(run -> accounts.importAccounts(run.id(), ProviderPayloads.accounts(p.resultPayload())));
    }

    public void onCompleted(OperationCompleted c) {
        if ("DISCOVER_ACCOUNTS".equals(c.operation())) {
            DiscoveryRunView run = store.findRunByOperation(c.operationId()).orElse(null);
            if (run == null || !"RUNNING".equals(run.status())) {
                return;
            }
            // A final page may arrive with the completion message.
            var last = ProviderPayloads.accounts(c.resultPayload());
            if ("SUCCESS".equals(c.status())) {
                if (!last.isEmpty()) {
                    accounts.importAccounts(run.id(), last);
                }
                accounts.completeDiscoveryRun(run.id());
            } else {
                accounts.failDiscoveryRun(run.id(), c.errorCode() + ": " + c.errorMessage());
            }
            return;
        }
        if (ACCOUNT_OPERATIONS.contains(c.operation()) && c.accountId() != null && "SUCCESS".equals(c.status())) {
            accounts.applyObservedState(c.accountId(), ProviderPayloads.observed(c.resultPayload()));
        }
    }
}
