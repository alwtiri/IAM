package com.enterprise.iam.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.account.api.AccountFindingView;
import com.enterprise.iam.core.account.api.DiscoveryRunView;
import com.enterprise.iam.core.account.application.AccountService;
import com.enterprise.iam.core.account.application.AccountStore;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.DiscoveredAccount;
import com.enterprise.iam.core.account.domain.FindingRules;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.IamException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Discovery import is read-only on targets, idempotent per native id, and drives findings (spec §12–§14, Phase 3 §9). */
class AccountServiceTest {

    static final UUID TARGET = UUID.randomUUID();
    static final UUID INSTANCE = UUID.randomUUID();
    static final UUID ALICE_IDENTITY = UUID.randomUUID();

    static final class MemoryAccounts implements AccountStore {
        final Map<UUID, Account> accounts = new LinkedHashMap<>();
        final Map<UUID, Map<FindingRules.Type, UUID>> open = new HashMap<>();
        final Map<UUID, AccountFindingView> findings = new LinkedHashMap<>();
        final Map<UUID, DiscoveryRunView> runs = new LinkedHashMap<>();

        @Override
        public void insert(Account a) {
            accounts.put(a.id(), a);
        }

        @Override
        public boolean update(Account a, long expectedVersion) {
            Account cur = accounts.get(a.id());
            if (cur == null || cur.version() != expectedVersion) {
                return false;
            }
            accounts.put(a.id(), new Account(a.id(), a.targetId(), a.providerInstanceId(), a.nativeId(), a.name(), a.displayName(), a.type(),
                    a.privileged(), a.privilegeReason(), a.ownerIdentityId(), a.linkedIdentityId(), a.governanceState(), a.nativeStatus(),
                    a.source(), a.attributes(), a.lastSeenAt(), a.lastLoginAt(), a.passwordLastSetAt(), expectedVersion + 1));
            return true;
        }

        @Override
        public Optional<Scoped> find(UUID id) {
            return Optional.ofNullable(accounts.get(id)).map(a -> new Scoped(a, "srv01", "/org/", "PRODUCTION", "linux-ssh"));
        }

        @Override
        public Map<String, Account> findByNativeIds(UUID targetId, UUID providerInstanceId, Collection<String> nativeIds) {
            return accounts.values().stream().filter(a -> nativeIds.contains(a.nativeId()))
                    .collect(Collectors.toMap(Account::nativeId, a -> a));
        }

        @Override
        public List<Account> findNotSeenSince(UUID targetId, UUID providerInstanceId, Instant before) {
            return accounts.values().stream().filter(a -> a.lastSeenAt() == null || a.lastSeenAt().isBefore(before)).toList();
        }

        @Override
        public List<Scoped> list(ScopeFilter filter, ListFilter f, PageRequest page) {
            return accounts.keySet().stream().map(id -> find(id).orElseThrow()).toList();
        }

        @Override
        public Optional<Binding> binding(UUID targetId, UUID providerInstanceId) {
            return Optional.of(new Binding(targetId, providerInstanceId, "/org/", "PRODUCTION", "linux-ssh"));
        }

        @Override
        public Map<FindingRules.Type, UUID> openFindings(UUID accountId) {
            return new EnumMap<>(open.getOrDefault(accountId, new EnumMap<>(FindingRules.Type.class)));
        }

        @Override
        public void openFinding(UUID id, UUID accountId, FindingRules.Type type, FindingRules.Severity severity, Instant at, Map<String, String> d) {
            open.computeIfAbsent(accountId, k -> new EnumMap<>(FindingRules.Type.class)).putIfAbsent(type, id);
            findings.put(id, new AccountFindingView(id, accountId, "x", TARGET, "srv01", type.name(), severity.name(), at, null, null, d));
        }

        @Override
        public void autoResolveFindings(UUID accountId, Set<FindingRules.Type> types, Instant at) {
            Map<FindingRules.Type, UUID> m = open.getOrDefault(accountId, new EnumMap<>(FindingRules.Type.class));
            types.forEach(m::remove);
        }

        @Override
        public Optional<FindingScoped> findFinding(UUID findingId) {
            return Optional.ofNullable(findings.get(findingId)).map(f -> new FindingScoped(f, "/org/", "PRODUCTION", "linux-ssh", INSTANCE));
        }

        @Override
        public boolean resolveFinding(UUID findingId, UUID resolvedBy, String resolution, Instant at) {
            AccountFindingView f = findings.get(findingId);
            findings.put(findingId, new AccountFindingView(f.id(), f.accountId(), f.accountName(), f.targetId(), f.targetName(), f.type(),
                    f.severity(), f.detectedAt(), at, resolution, f.details()));
            open.getOrDefault(f.accountId(), new EnumMap<>(FindingRules.Type.class)).remove(FindingRules.Type.valueOf(f.type()));
            return true;
        }

        @Override
        public List<FindingScoped> listFindings(ScopeFilter filter, String type, String severity, boolean openOnly, PageRequest page) {
            return findings.keySet().stream().map(id -> findFinding(id).orElseThrow()).toList();
        }

        @Override
        public void insertRun(UUID id, UUID providerInstanceId, UUID targetId, UUID operationId, Instant startedAt) {
            runs.put(id, new DiscoveryRunView(id, providerInstanceId, targetId, operationId, "RUNNING", startedAt, null, 0, 0, 0, 0, null));
        }

        @Override
        public Optional<DiscoveryRunView> findRun(UUID id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public void addRunCounts(UUID id, int seen, int created, int groups) {
            DiscoveryRunView r = runs.get(id);
            runs.put(id, new DiscoveryRunView(r.id(), r.providerInstanceId(), r.targetId(), r.operationId(), r.status(), r.startedAt(), null,
                    r.accountsSeen() + seen, r.accountsNew() + created, 0, r.groupsSeen() + groups, null));
        }

        @Override
        public void finishRun(UUID id, String status, int removed, String errorMessage, Instant at) {
            DiscoveryRunView r = runs.get(id);
            runs.put(id, new DiscoveryRunView(r.id(), r.providerInstanceId(), r.targetId(), r.operationId(), status, r.startedAt(), at,
                    r.accountsSeen(), r.accountsNew(), removed, r.groupsSeen(), errorMessage));
        }

        @Override
        public boolean hasCompletedRun(UUID targetId, UUID providerInstanceId, UUID exceptRunId) {
            return runs.values().stream().anyMatch(r -> "COMPLETED".equals(r.status()) && !r.id().equals(exceptRunId));
        }

        @Override
        public List<DiscoveryRunView> runs(UUID targetId, int limit) {
            return new ArrayList<>(runs.values());
        }

        Account byName(String name) {
            return accounts.values().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
        }
    }

    MemoryAccounts store;
    List<AuditEntry> audited;
    TestSupport.MutableClock clock;
    AccountService service;
    String aliceState = "ACTIVE";

    @BeforeEach
    void setUp() {
        store = new MemoryAccounts();
        audited = new ArrayList<>();
        clock = new TestSupport.MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
        IdentityDirectory identities = new IdentityDirectory() {
            @Override
            public Optional<IdentitySummary> find(UUID identityId) {
                return ALICE_IDENTITY.equals(identityId) ? Optional.of(alice()) : Optional.empty();
            }

            @Override
            public Optional<IdentitySummary> findByUsername(String username) {
                return "alice".equals(username) ? Optional.of(alice()) : Optional.empty();
            }
        };
        service = new AccountService(store, identities, TestSupport.guard(true), (actor, e) -> audited.add(e), TestSupport.DIRECT_TX,
                clock, Duration.ofDays(90));
    }

    IdentitySummary alice() {
        return new IdentitySummary(ALICE_IDENTITY, UUID.randomUUID(), "alice", "Alice", "alice@example.org", "EMPLOYEE", aliceState, null,
                UUID.randomUUID(), "/org/");
    }

    static DiscoveredAccount discovered(String name, boolean privileged) {
        return new DiscoveredAccount("uid:" + name, name, null, Account.NativeStatus.ENABLED, privileged, privileged ? "wheel" : null,
                null, null, Map.of("shell", "/bin/bash"), List.of());
    }

    DiscoveryRunView runDiscovery(DiscoveredAccount... accounts) {
        UUID run = service.startDiscoveryRun(TARGET, INSTANCE, UUID.randomUUID());
        clock.advanceSeconds(1);
        service.importAccounts(run, List.of(accounts));
        clock.advanceSeconds(1);
        return service.completeDiscoveryRun(run);
    }

    @Test
    void firstDiscoveryImportsLinksAndRaisesFindings() {
        DiscoveryRunView run = runDiscovery(discovered("alice", false), discovered("backup", true));
        assertEquals("COMPLETED", run.status());
        assertEquals(2, run.accountsSeen());
        assertEquals(2, run.accountsNew());

        Account alice = store.byName("alice");
        assertEquals(ALICE_IDENTITY, alice.linkedIdentityId());
        assertEquals(Account.Type.HUMAN, alice.type());
        assertEquals(Account.GovernanceState.DISCOVERED, alice.governanceState());
        assertTrue(store.openFindings(alice.id()).isEmpty());

        Account backup = store.byName("backup");
        assertNull(backup.linkedIdentityId());
        assertEquals(Set.of(FindingRules.Type.ORPHAN, FindingRules.Type.UNMANAGED, FindingRules.Type.PRIVILEGED_WITHOUT_OWNER),
                store.openFindings(backup.id()).keySet());
        assertTrue(audited.stream().anyMatch(e -> e.action().equals("account.discovery-completed")));
    }

    @Test
    void rediscoveryIsIdempotentAndMarksMissingAccountsAbsent() {
        runDiscovery(discovered("alice", false), discovered("backup", true));
        UUID backupId = store.byName("backup").id();
        clock.advanceSeconds(60);
        DiscoveryRunView second = runDiscovery(discovered("alice", false));
        assertEquals(0, second.accountsNew());
        assertEquals(1, second.accountsRemoved());
        assertEquals(2, store.accounts.size());
        Account backup = store.accounts.get(backupId);
        assertEquals(Account.NativeStatus.ABSENT, backup.nativeStatus());
        assertEquals(Account.GovernanceState.REMOVED, backup.governanceState());
        assertTrue(store.openFindings(backupId).isEmpty(), "findings of a removed account are auto-resolved");
    }

    @Test
    void newAccountOnBaselinedTargetIsUnexpected() {
        runDiscovery(discovered("alice", false));
        clock.advanceSeconds(60);
        runDiscovery(discovered("alice", false), discovered("intruder", false));
        assertTrue(store.openFindings(store.byName("intruder").id()).containsKey(FindingRules.Type.UNEXPECTED));
    }

    @Test
    void disabledIdentityWithEnabledAccountIsCritical() {
        aliceState = "DISABLED";
        runDiscovery(discovered("alice", false));
        assertTrue(store.openFindings(store.byName("alice").id()).containsKey(FindingRules.Type.DISABLED_IDENTITY_ACTIVE_ACCOUNT));
    }

    @Test
    void governanceChangeClearsOrphanAndIsAudited() {
        runDiscovery(discovered("svc-backup", false));
        Account a = store.byName("svc-backup");
        assertTrue(store.openFindings(a.id()).containsKey(FindingRules.Type.ORPHAN));
        var view = service.changeGovernance(TestSupport.actor(UUID.randomUUID()), a.id(),
                new AccountService.GovernanceChange(Account.Type.SERVICE, ALICE_IDENTITY, null, Account.GovernanceState.GOVERNED, "reviewed"),
                a.version());
        assertEquals("SERVICE", view.type());
        assertEquals("GOVERNED", view.governanceState());
        assertTrue(view.openFindings().isEmpty());
        assertTrue(audited.stream().anyMatch(e -> e.action().equals("account.governance-changed")));
    }

    @Test
    void managedStateCannotBeSetByHandAndUnknownIdentitiesAreRejected() {
        runDiscovery(discovered("svc", false));
        Account a = store.byName("svc");
        var actor = TestSupport.actor(UUID.randomUUID());
        IamException e = assertThrows(IamException.class, () -> service.changeGovernance(actor, a.id(),
                new AccountService.GovernanceChange(null, null, null, Account.GovernanceState.MANAGED, null), a.version()));
        assertEquals("VALIDATION_FAILED", e.code().name());
        assertThrows(IamException.class, () -> service.changeGovernance(actor, a.id(),
                new AccountService.GovernanceChange(null, UUID.randomUUID(), null, null, null), a.version()));
    }

    @Test
    void staleVersionIsRejected() {
        runDiscovery(discovered("svc", false));
        Account a = store.byName("svc");
        IamException e = assertThrows(IamException.class, () -> service.changeGovernance(TestSupport.actor(UUID.randomUUID()), a.id(),
                new AccountService.GovernanceChange(Account.Type.SERVICE, null, null, null, null), a.version() + 5));
        assertEquals("CONCURRENT_MODIFICATION", e.code().name());
    }

    @Test
    void outOfScopeAccountIsNotFound() {
        runDiscovery(discovered("svc", false));
        Account a = store.byName("svc");
        AccountService denied = new AccountService(store, new IdentityDirectory() {
            @Override
            public Optional<IdentitySummary> find(UUID identityId) {
                return Optional.empty();
            }
        }, TestSupport.guard(false), (actor, e) -> { }, TestSupport.DIRECT_TX, clock, Duration.ofDays(90));
        IamException e = assertThrows(IamException.class, () -> denied.get(TestSupport.actor(UUID.randomUUID()), a.id()));
        assertEquals("NOT_FOUND", e.code().name());
    }

    @Test
    void resolvingAFindingNeedsAReasonAndIsAudited() {
        runDiscovery(discovered("backup", true));
        UUID findingId = store.findings.keySet().iterator().next();
        var actor = TestSupport.actor(UUID.randomUUID());
        assertThrows(IamException.class, () -> service.resolveFinding(actor, findingId, " "));
        AccountFindingView resolved = service.resolveFinding(actor, findingId, "accepted risk, ticket CHG-1");
        assertEquals("accepted risk, ticket CHG-1", resolved.resolution());
        assertTrue(audited.stream().anyMatch(e -> e.action().equals("account.finding-resolved")));
        assertThrows(IamException.class, () -> service.resolveFinding(actor, findingId, "again"));
    }
}
