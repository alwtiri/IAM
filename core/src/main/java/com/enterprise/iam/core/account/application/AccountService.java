package com.enterprise.iam.core.account.application;

import com.enterprise.iam.core.account.api.AccountFindingView;
import com.enterprise.iam.core.account.api.AccountView;
import com.enterprise.iam.core.account.api.DiscoveryRunView;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.DiscoveredAccount;
import com.enterprise.iam.core.account.domain.FindingRules;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Account Management Core (spec §12–§14, G8): account inventory, governance, findings, and read-only discovery import.
 *
 * <p>Scope of an account = owning org unit and environment of its target + provider type + provider instance + target.
 * Reads of out-of-scope accounts answer 404. Discovery import runs as the system actor and never changes targets.
 */
public class AccountService {

    public record GovernanceChange(Account.Type type, UUID ownerIdentityId, UUID linkedIdentityId, Account.GovernanceState governanceState,
                                   String reason) {
    }

    private static final CurrentActor SYSTEM = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);

    private final AccountStore store;
    private final IdentityDirectory identities;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;
    private final Duration dormantAfter;

    public AccountService(AccountStore store, IdentityDirectory identities, AccessGuard guard, AuditRecorder audit, TransactionRunner tx,
                          Clock clock, Duration dormantAfter) {
        this.store = store;
        this.identities = identities;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.dormantAfter = dormantAfter;
    }

    // ------------------------------------------------------------------ queries

    public PageResult<AccountView> list(CurrentActor actor, AccountStore.ListFilter filter, PageRequest page) {
        var scope = guard.filter(actor, Permissions.ACCOUNT_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.list(scope, filter, page), page.limit(), s -> s.account().id())
                .map(s -> view(s, store.openFindings(s.account().id()).keySet())));
    }

    public AccountView get(CurrentActor actor, UUID id) {
        return tx.readOnly(() -> {
            AccountStore.Scoped s = store.find(id).orElseThrow(() -> IamException.notFound("Account"));
            guard.require(actor, Permissions.ACCOUNT_READ, scope(s), true);
            return view(s, store.openFindings(id).keySet());
        });
    }

    public PageResult<AccountFindingView> findings(CurrentActor actor, String type, String severity, boolean openOnly, PageRequest page) {
        var scope = guard.filter(actor, Permissions.ACCOUNT_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.listFindings(scope, type, severity, openOnly, page), page.limit(),
                f -> f.finding().id())).map(AccountStore.FindingScoped::finding);
    }

    public List<DiscoveryRunView> discoveryRuns(CurrentActor actor, UUID targetId, ResourceScope targetScope) {
        guard.require(actor, Permissions.ACCOUNT_READ, targetScope, true);
        return tx.readOnly(() -> store.runs(targetId, 20));
    }

    // ------------------------------------------------------------------ governance

    public AccountView changeGovernance(CurrentActor actor, UUID id, GovernanceChange change, long expectedVersion) {
        return tx.inTransaction(() -> {
            AccountStore.Scoped s = store.find(id).orElseThrow(() -> IamException.notFound("Account"));
            guard.require(actor, Permissions.ACCOUNT_WRITE, scope(s), true);
            if (change.governanceState() == Account.GovernanceState.MANAGED || change.governanceState() == Account.GovernanceState.REMOVED) {
                throw IamException.validation("governanceState", "NOT_SETTABLE",
                        "MANAGED is set by credential onboarding and REMOVED by discovery");
            }
            requireIdentity("ownerIdentityId", change.ownerIdentityId());
            requireIdentity("linkedIdentityId", change.linkedIdentityId());
            Account cur = s.account();
            Account next = cur.governed(change.type() == null ? cur.type() : change.type(), change.ownerIdentityId(),
                    change.linkedIdentityId(), change.governanceState() == null ? cur.governanceState() : change.governanceState());
            if (!store.update(next, expectedVersion)) {
                throw IamException.concurrentModification("Account");
            }
            Map<String, String> details = new HashMap<>();
            details.put("type", next.type().name());
            details.put("governanceState", next.governanceState().name());
            details.put("owner", String.valueOf(next.ownerIdentityId()));
            details.put("linkedIdentity", String.valueOf(next.linkedIdentityId()));
            audit.record(actor, new AuditEntry("account.governance-changed", "account", id.toString(), cur.targetId(),
                    AuditEntry.Result.SUCCESS, change.reason(), cur.providerInstanceId(), details));
            Instant now = clock.instant();
            evaluateFindings(next, false, now); // UNEXPECTED is raised only on first sight during discovery
            AccountStore.Scoped updated = store.find(id).orElseThrow();
            return view(updated, store.openFindings(id).keySet());
        });
    }

    public AccountFindingView resolveFinding(CurrentActor actor, UUID findingId, String resolution) {
        if (resolution == null || resolution.isBlank() || resolution.length() > 500) {
            throw IamException.validation("resolution", "REQUIRED", "a resolution of 1..500 characters is required");
        }
        return tx.inTransaction(() -> {
            AccountStore.FindingScoped f = store.findFinding(findingId).orElseThrow(() -> IamException.notFound("Finding"));
            ResourceScope scope = new ResourceScope(f.orgUnitPath(), f.environment(), f.providerType(), f.providerInstanceId(),
                    f.finding().targetId());
            guard.require(actor, Permissions.ACCOUNT_FINDING_RESOLVE, scope, true);
            if (f.finding().resolvedAt() != null) {
                throw IamException.invalidTransition("Finding", "RESOLVED", "RESOLVED");
            }
            store.resolveFinding(findingId, actor.identityId(), resolution, clock.instant());
            audit.record(actor, new AuditEntry("account.finding-resolved", "account-finding", findingId.toString(), f.finding().targetId(),
                    AuditEntry.Result.SUCCESS, resolution, f.providerInstanceId(),
                    Map.of("type", f.finding().type(), "account", f.finding().accountId().toString())));
            return store.findFinding(findingId).orElseThrow().finding();
        });
    }

    // ------------------------------------------------------------------ discovery import (system actor)

    /** Starts a discovery run; the caller has already authorized the discovery request. */
    public UUID startDiscoveryRun(UUID targetId, UUID providerInstanceId, UUID operationId) {
        return tx.inTransaction(() -> {
            store.binding(targetId, providerInstanceId)
                    .orElseThrow(() -> IamException.validation("providerInstanceId", "NOT_BOUND", "provider instance is not bound to the target"));
            UUID runId = Ids.newId(clock);
            store.insertRun(runId, providerInstanceId, targetId, operationId, clock.instant());
            audit.record(SYSTEM, new AuditEntry("account.discovery-started", "discovery-run", runId.toString(), targetId,
                    AuditEntry.Result.SUCCESS, null, providerInstanceId, Map.of("operation", String.valueOf(operationId))));
            return runId;
        });
    }

    /**
     * Imports one page of discovered accounts. New accounts are DISCOVERED (source DISCOVERY) and linked to an identity when
     * the account name equals an identity username; existing accounts only get their native fields refreshed.
     */
    public void importAccounts(UUID runId, List<DiscoveredAccount> page) {
        tx.run(() -> {
            DiscoveryRunView run = store.findRun(runId).orElseThrow(() -> IamException.notFound("Discovery run"));
            if (!"RUNNING".equals(run.status())) {
                throw IamException.invalidTransition("Discovery run", run.status(), "RUNNING");
            }
            boolean baselined = store.hasCompletedRun(run.targetId(), run.providerInstanceId(), runId);
            Instant now = clock.instant();
            List<String> ids = page.stream().map(DiscoveredAccount::nativeId).toList();
            Map<String, Account> existing = store.findByNativeIds(run.targetId(), run.providerInstanceId(), ids);
            int created = 0;
            for (DiscoveredAccount d : page) {
                Account cur = existing.get(d.nativeId());
                if (cur == null) {
                    Optional<IdentitySummary> linked = identities.findByUsername(d.name());
                    Account a = new Account(Ids.newId(clock), run.targetId(), run.providerInstanceId(), d.nativeId(), d.name(),
                            d.displayName(), linked.isPresent() ? Account.Type.HUMAN : Account.Type.UNKNOWN, d.privileged(),
                            d.privilegeReason(), null, linked.map(IdentitySummary::id).orElse(null), Account.GovernanceState.DISCOVERED,
                            d.nativeStatus(), Account.Source.DISCOVERY, d.attributes(), now, d.lastLoginAt(), d.passwordLastSetAt(), 0);
                    store.insert(a);
                    created++;
                    evaluateFindings(a, baselined, now);
                } else {
                    Account next = cur.observed(d, now);
                    if (!store.update(next, cur.version())) {
                        throw IamException.concurrentModification("Account");
                    }
                    evaluateFindings(next, false, now);
                }
            }
            store.addRunCounts(runId, page.size(), created, 0);
        });
    }

    /** Completes a run: accounts of this target/instance not seen since the run started are marked ABSENT. */
    public DiscoveryRunView completeDiscoveryRun(UUID runId) {
        return tx.inTransaction(() -> {
            DiscoveryRunView run = store.findRun(runId).orElseThrow(() -> IamException.notFound("Discovery run"));
            Instant now = clock.instant();
            int removed = 0;
            for (Account a : store.findNotSeenSince(run.targetId(), run.providerInstanceId(), run.startedAt())) {
                if (a.nativeStatus() == Account.NativeStatus.ABSENT) {
                    continue;
                }
                Account gone = a.absent();
                if (store.update(gone, a.version())) {
                    removed++;
                    store.autoResolveFindings(a.id(), EnumSetAll.ALL, now);
                }
            }
            store.finishRun(runId, "COMPLETED", removed, null, now);
            DiscoveryRunView done = store.findRun(runId).orElseThrow();
            audit.record(SYSTEM, new AuditEntry("account.discovery-completed", "discovery-run", runId.toString(), run.targetId(),
                    AuditEntry.Result.SUCCESS, null, run.providerInstanceId(),
                    Map.of("seen", String.valueOf(done.accountsSeen()), "new", String.valueOf(done.accountsNew()),
                            "removed", String.valueOf(removed))));
            return done;
        });
    }

    public void failDiscoveryRun(UUID runId, String errorMessage) {
        tx.run(() -> {
            DiscoveryRunView run = store.findRun(runId).orElseThrow(() -> IamException.notFound("Discovery run"));
            String msg = errorMessage == null ? "unknown error" : errorMessage.substring(0, Math.min(500, errorMessage.length()));
            store.finishRun(runId, "FAILED", 0, msg, clock.instant());
            audit.record(SYSTEM, new AuditEntry("account.discovery-failed", "discovery-run", runId.toString(), run.targetId(),
                    AuditEntry.Result.FAILURE, msg, run.providerInstanceId(), Map.of()));
        });
    }

    /** Refreshes an account's native state from a verified lifecycle-operation result. */
    public void applyObservedState(UUID accountId, DiscoveredAccount observed) {
        if (observed == null) {
            return;
        }
        tx.run(() -> {
            AccountStore.Scoped s = store.find(accountId).orElse(null);
            if (s == null) {
                return;
            }
            Account next = s.account().observed(observed, clock.instant());
            if (store.update(next, s.account().version())) {
                evaluateFindings(next, false, clock.instant());
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void evaluateFindings(Account a, boolean newOnBaselinedTarget, Instant now) {
        FindingRules.LinkedIdentity linked = a.linkedIdentityId() == null ? null
                : identities.find(a.linkedIdentityId()).map(i -> new FindingRules.LinkedIdentity(i.state())).orElse(null);
        Map<FindingRules.Type, FindingRules.Severity> wanted = FindingRules.evaluate(a, linked,
                new FindingRules.Context(now, dormantAfter, newOnBaselinedTarget));
        Map<FindingRules.Type, UUID> open = store.openFindings(a.id());
        Set<FindingRules.Type> cleared = new HashSet<>(open.keySet());
        cleared.removeAll(wanted.keySet());
        // UNEXPECTED is only raised on first sight; it stays open until resolved by a person.
        cleared.remove(FindingRules.Type.UNEXPECTED);
        if (!cleared.isEmpty()) {
            store.autoResolveFindings(a.id(), cleared, now);
        }
        for (var e : wanted.entrySet()) {
            if (!open.containsKey(e.getKey())) {
                store.openFinding(Ids.newId(clock), a.id(), e.getKey(), e.getValue(), now, Map.of("account", a.name()));
            }
        }
    }

    private void requireIdentity(String field, UUID id) {
        if (id != null && identities.find(id).isEmpty()) {
            throw IamException.validation(field, "NOT_FOUND", "identity does not exist");
        }
    }

    static ResourceScope scope(AccountStore.Scoped s) {
        return new ResourceScope(s.orgUnitPath(), s.environment(), s.providerType(), s.account().providerInstanceId(), s.account().targetId());
    }

    static AccountView view(AccountStore.Scoped s, Set<FindingRules.Type> openFindings) {
        Account a = s.account();
        List<String> findings = new ArrayList<>(openFindings.stream().map(Enum::name).sorted().toList());
        return new AccountView(a.id(), a.targetId(), s.targetName(), a.providerInstanceId(), s.providerType(), a.nativeId(), a.name(),
                a.displayName(), a.type().name(), a.privileged(), a.privilegeReason(), a.ownerIdentityId(), a.linkedIdentityId(),
                a.governanceState().name(), a.nativeStatus().name(), a.source().name(), a.attributes(), a.lastSeenAt(), a.lastLoginAt(),
                a.passwordLastSetAt(), findings, a.version());
    }

    private static final class EnumSetAll {
        static final Set<FindingRules.Type> ALL = java.util.EnumSet.allOf(FindingRules.Type.class);
    }
}
