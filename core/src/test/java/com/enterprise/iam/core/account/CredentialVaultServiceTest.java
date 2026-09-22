package com.enterprise.iam.core.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.account.api.CheckoutView;
import com.enterprise.iam.core.account.api.CredentialCheckoutEnded;
import com.enterprise.iam.core.account.api.RevealedCredential;
import com.enterprise.iam.core.account.application.AccountStore;
import com.enterprise.iam.core.account.application.CredentialVaultService;
import com.enterprise.iam.core.account.application.CredentialVaultStore;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.PasswordGenerator;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.operation.api.OperationCommands;
import com.enterprise.iam.core.operation.api.OperationCompleted;
import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.provider.api.ProviderDirectory;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Secret;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialVaultServiceTest {

    static final class MemoryVault implements CredentialVaultStore {
        final Map<UUID, Vaulted> rows = new HashMap<>();
        final Map<UUID, Checkout> checkouts = new LinkedHashMap<>();

        @Override
        public Optional<Vaulted> find(UUID accountId) {
            return Optional.ofNullable(rows.get(accountId));
        }

        @Override
        public Optional<Vaulted> lock(UUID accountId) {
            return find(accountId);
        }

        @Override
        public Optional<Vaulted> findByOperation(UUID operationId) {
            return rows.values().stream().filter(v -> operationId.equals(v.rotationOperationId())).findFirst();
        }

        @Override
        public List<Vaulted> all() {
            return List.copyOf(rows.values());
        }

        @Override
        public void insert(Vaulted v, Instant now) {
            rows.put(v.accountId(), v);
        }

        @Override
        public boolean update(Vaulted v, Instant now) {
            Vaulted cur = rows.get(v.accountId());
            if (cur == null || cur.version() != v.version()) {
                return false;
            }
            rows.put(v.accountId(), new Vaulted(v.accountId(), v.secretPath(), v.currentRef(), v.pendingRef(), v.rotationStatus(),
                    v.rotationOperationId(), v.rotationTrigger(), v.lastError(), v.lastRotatedAt(), v.rotationIntervalDays(), v.managedBy(),
                    v.createdAt(), v.version() + 1));
            return true;
        }

        @Override
        public void insertCheckout(Checkout c) {
            checkouts.put(c.id(), c);
        }

        @Override
        public Optional<Checkout> findCheckout(UUID id) {
            return Optional.ofNullable(checkouts.get(id));
        }

        @Override
        public Optional<Checkout> activeCheckout(UUID accountId) {
            return checkouts.values().stream().filter(c -> c.accountId().equals(accountId) && "ACTIVE".equals(c.status())).findFirst();
        }

        @Override
        public boolean endCheckout(UUID id, String status, UUID endedBy, Instant at) {
            Checkout c = checkouts.get(id);
            if (c == null || !"ACTIVE".equals(c.status())) {
                return false;
            }
            checkouts.put(id, new Checkout(c.id(), c.accountId(), c.identityId(), c.requestId(), c.reason(), c.startedAt(), c.notAfter(), status, at,
                    endedBy, c.revealCount(), c.lastRevealedAt()));
            return true;
        }

        @Override
        public void recordReveal(UUID id, Instant at) {
            Checkout c = checkouts.get(id);
            checkouts.put(id, new Checkout(c.id(), c.accountId(), c.identityId(), c.requestId(), c.reason(), c.startedAt(), c.notAfter(), c.status(),
                    c.endedAt(), c.endedBy(), c.revealCount() + 1, at));
        }

        @Override
        public List<Checkout> checkoutsOf(UUID identityId, int limit) {
            return checkouts.values().stream().filter(c -> c.identityId().equals(identityId)).toList();
        }

        @Override
        public List<Checkout> recentCheckouts(int limit) {
            return checkouts.values().stream().sorted(Comparator.comparing(Checkout::startedAt).reversed()).toList();
        }

        @Override
        public List<Checkout> overdue(Instant now) {
            return checkouts.values().stream().filter(c -> "ACTIVE".equals(c.status()) && !c.notAfter().isAfter(now)).toList();
        }
    }

    /** Vault with versions per path; values are kept so reveal can be checked. */
    static final class FakeSecrets implements SecretStore {
        final Map<String, String> values = new HashMap<>();
        final Map<String, Integer> versions = new HashMap<>();

        @Override
        public SecretRef write(String path, Secret value) {
            int v = versions.merge(path, 1, Integer::sum);
            SecretRef ref = SecretRef.of("iam", path, v);
            values.put(ref.value(), new String(value.reveal()));
            return ref;
        }

        @Override
        public Secret read(SecretRef ref) {
            return Secret.of(values.get(ref.value()));
        }

        @Override
        public void destroy(SecretRef ref) {
        }
    }

    final Instant now = Instant.parse("2026-09-22T10:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final UUID admin = UUID.randomUUID();
    final UUID alice = UUID.randomUUID();
    final UUID instance = UUID.randomUUID();
    final Account root = new Account(UUID.randomUUID(), UUID.randomUUID(), instance, "0", "root", null, Account.Type.SYSTEM, true, "uid 0",
            null, null, Account.GovernanceState.DISCOVERED, Account.NativeStatus.ENABLED, Account.Source.DISCOVERY, Map.of(), now, null, null, 0);
    final MemoryVault store = new MemoryVault();
    final FakeSecrets secrets = new FakeSecrets();
    final List<ProviderCommand> commands = new ArrayList<>();
    final List<Map<String, SecretRef>> issued = new ArrayList<>();
    final List<AuditEntry> audit = new ArrayList<>();
    final List<Object> events = new ArrayList<>();

    final AccountStore accounts = (AccountStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AccountStore.class},
            (p, m, args) -> {
                if (m.getName().equals("find")) {
                    return args[0].equals(root.id()) ? Optional.of(new AccountStore.Scoped(root, "srv01", "/IT", "PROD", "linux-ssh")) : Optional.empty();
                }
                throw new UnsupportedOperationException(m.getName());
            });
    final OperationCommands operations = new OperationCommands() {
        @Override
        public UUID create(ProviderCommand command) {
            commands.add(command);
            return UUID.randomUUID();
        }

        @Override
        public void dispatch(UUID operationId, ProviderCommand command, Map<String, String> credentialHandles) {
        }
    };
    final ProviderDirectory providers = new ProviderDirectory() {
        @Override
        public Optional<Connection> connection(UUID id) {
            return Optional.of(new Connection(id, "linux-ssh", "ssh://srv01:22", Map.of("username", "svc-iam"), "vault:iam/providers/x/connection#1", true));
        }

        @Override
        public boolean isBound(UUID targetId, UUID providerInstanceId) {
            return true;
        }
    };
    final IdentityDirectory identities = new IdentityDirectory() {
        @Override
        public Optional<IdentitySummary> find(UUID identityId) {
            return Optional.empty();
        }
    };
    final CredentialVaultService vault = new CredentialVaultService(accounts, store, secrets, (op, type, purposes, ttl) -> {
        issued.add(purposes);
        return Map.of("connection", "ch_1", "new-secret", "ch_2");
    }, operations, providers, identities, TestSupport.guard(true), (a, e) -> audit.add(e), events::add, TestSupport.DIRECT_TX, clock,
            new PasswordGenerator(new SecureRandom()), Duration.ofDays(30));

    private UUID lastOperation() {
        return store.rows.get(root.id()).rotationOperationId();
    }

    private void complete(String status) {
        vault.onCompleted(new OperationCompleted(lastOperation(), "ROTATE_PASSWORD", status, instance, root.targetId(), root.id(), Map.of(),
                "SUCCESS".equals(status) ? null : "X", "SUCCESS".equals(status) ? null : "x"));
    }

    @Test
    void managingRotatesAndPromotesThePendingVersionOnlyAfterVerification() {
        vault.manage(TestSupport.actor(admin), root.id(), "onboard");
        assertEquals("ROTATE_PASSWORD", commands.get(0).operation());
        assertTrue(commands.get(0).mutating());
        assertEquals(List.of("connection", "new-secret"), issued.get(0).keySet().stream().sorted().toList());
        var row = store.rows.get(root.id());
        assertEquals("ROTATING", row.rotationStatus());
        assertNull(row.currentRef(), "nothing is current before verification");
        String generated = secrets.values.get(row.pendingRef());
        assertEquals(PasswordGenerator.LENGTH, generated.length());
        assertThrows(IamException.class, () -> vault.checkoutDirect(TestSupport.actor(admin), root.id(), 2, "x"), "not verified yet");
        complete("SUCCESS");
        row = store.rows.get(root.id());
        assertEquals("VERIFIED", row.rotationStatus());
        assertEquals("vault:iam/accounts/" + root.id() + "/password#1", row.currentRef());
        assertTrue(audit.stream().noneMatch(e -> e.details().containsValue(generated)), "the password is never audited");
    }

    @Test
    void unconfirmedRotationKeepsBothVersionsAndRevealOffersTheAlternate() {
        vault.manage(TestSupport.actor(admin), root.id(), null);
        complete("SUCCESS");
        vault.rotate(TestSupport.actor(admin), root.id(), "manual");
        complete("UNKNOWN");
        var row = store.rows.get(root.id());
        assertEquals("UNKNOWN", row.rotationStatus());
        assertTrue(row.currentRef().endsWith("#1") && row.pendingRef().endsWith("#2"));
        CheckoutView c = vault.checkoutDirect(TestSupport.actor(admin), root.id(), 2, "incident");
        RevealedCredential r = vault.reveal(TestSupport.actor(admin), c.id());
        assertEquals(secrets.values.get(row.currentRef()), r.password());
        assertEquals(secrets.values.get(row.pendingRef()), r.alternatePassword());
        assertTrue(!r.toString().contains(r.password()), "toString never prints the password");
    }

    @Test
    void checkoutIsExclusiveHolderOnlyAndCheckInRotates() {
        vault.manage(TestSupport.actor(admin), root.id(), null);
        complete("SUCCESS");
        UUID checkout = vault.grant(root.id(), alice, UUID.randomUUID(), Duration.ofHours(4), "incident 42");
        assertThrows(IamException.class, () -> vault.grant(root.id(), admin, null, Duration.ofHours(1), "x"), "exclusive");
        assertThrows(IamException.class, () -> vault.reveal(TestSupport.actor(admin), checkout), "only the holder reveals");
        assertThrows(IamException.class, () -> vault.rotate(TestSupport.actor(admin), root.id(), "x"), "no rotation while checked out");
        String first = vault.reveal(TestSupport.actor(alice), checkout).password();
        int before = commands.size();
        CheckoutView in = vault.checkIn(TestSupport.actor(alice), checkout);
        assertEquals("CHECKED_IN", in.status());
        assertEquals(1, in.revealCount());
        assertEquals(before + 1, commands.size(), "check-in after a reveal rotates");
        assertEquals("CHECK_IN", store.rows.get(root.id()).rotationTrigger());
        assertTrue(events.contains(new CredentialCheckoutEnded(checkout, in.requestId(), root.id(), "CHECKED_IN")));
        complete("SUCCESS");
        assertNotEquals(first, secrets.values.get(store.rows.get(root.id()).currentRef()));
    }

    @Test
    void overdueCheckoutsExpireWithoutRotationWhenNeverRevealed() {
        vault.manage(TestSupport.actor(admin), root.id(), null);
        complete("SUCCESS");
        store.insertCheckout(new CredentialVaultStore.Checkout(UUID.randomUUID(), root.id(), alice, null, "x", now.minusSeconds(7200),
                now.minusSeconds(60), "ACTIVE", null, null, 0, null));
        int before = commands.size();
        assertEquals(1, vault.expireCheckouts());
        assertEquals(before, commands.size());
        assertTrue(store.activeCheckout(root.id()).isEmpty());
    }

    @Test
    void failedRotationKeepsTheOldPassword() {
        vault.manage(TestSupport.actor(admin), root.id(), null);
        complete("SUCCESS");
        String current = store.rows.get(root.id()).currentRef();
        vault.rotate(TestSupport.actor(admin), root.id(), null);
        complete("FAILED");
        var row = store.rows.get(root.id());
        assertEquals("FAILED", row.rotationStatus());
        assertEquals(current, row.currentRef());
        assertNull(row.pendingRef());
    }

    @Test
    void unmanageStopsRotationAndCanBeManagedAgain() {
        vault.manage(TestSupport.actor(admin), root.id(), null);
        assertThrows(IamException.class, () -> vault.unmanage(TestSupport.actor(admin), root.id(), "x"), "not while rotating");
        complete("SUCCESS");
        vault.unmanage(TestSupport.actor(admin), root.id(), "decommissioned");
        assertNull(store.rows.get(root.id()).secretPath());
        assertTrue(vault.checkoutTargets().isEmpty() || vault.checkoutTargets().stream().noneMatch(t -> t.accountId().equals(root.id()) && t.available()));
        vault.manage(TestSupport.actor(admin), root.id(), "again");
        assertEquals("ROTATING", store.rows.get(root.id()).rotationStatus());
    }
}
