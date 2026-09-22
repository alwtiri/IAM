package com.enterprise.iam.core.account.application;

import com.enterprise.iam.core.account.api.CheckoutView;
import com.enterprise.iam.core.account.api.CredentialCheckoutEnded;
import com.enterprise.iam.core.account.api.CredentialCheckouts;
import com.enterprise.iam.core.account.api.RevealedCredential;
import com.enterprise.iam.core.account.api.VaultedCredentialView;
import com.enterprise.iam.core.account.application.CredentialVaultStore.Checkout;
import com.enterprise.iam.core.account.application.CredentialVaultStore.Vaulted;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.PasswordGenerator;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.operation.api.OperationCommands;
import com.enterprise.iam.core.operation.api.OperationCompleted;
import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.provider.api.ProviderDirectory;
import com.enterprise.iam.core.secrets.api.CredentialHandles;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import com.enterprise.iam.kernel.Secret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Privileged credential vault (Phase 6.1, ADR-0021).
 *
 * <p>Taking an account under management rotates its password immediately to a generated value the platform keeps in Vault
 * only. Every rotation writes the new value as a new Vault version first (pending), sends ROTATE_PASSWORD with two
 * single-use handles (connection, new-secret), and promotes the pending version to current only after the provider
 * verified the change (G5). An unconfirmed rotation keeps both versions so the password can never be lost.
 *
 * <p>Checkouts are exclusive and time-bound. Only the holder can reveal the password, with step-up (enforced at the
 * endpoint), and every reveal is audited. Check-in, expiry and revocation rotate the password when it was revealed.
 */
public class CredentialVaultService implements CredentialCheckouts {

    public static final Duration MAX_CHECKOUT = Duration.ofHours(72);
    static final Duration ROTATION_TIMEOUT = Duration.ofMinutes(3);
    static final Duration HANDLE_TTL = Duration.ofMinutes(10);
    private static final CurrentActor SYSTEM = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);
    private static final int LIST_LIMIT = 200;

    private final AccountStore accounts;
    private final CredentialVaultStore store;
    private final SecretStore secrets;
    private final CredentialHandles handles;
    private final OperationCommands operations;
    private final ProviderDirectory providers;
    private final IdentityDirectory identities;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final DomainEventPublisher events;
    private final TransactionRunner tx;
    private final Clock clock;
    private final PasswordGenerator generator;
    private final Duration defaultInterval;

    public CredentialVaultService(AccountStore accounts, CredentialVaultStore store, SecretStore secrets, CredentialHandles handles,
                                  OperationCommands operations, ProviderDirectory providers, IdentityDirectory identities, AccessGuard guard,
                                  AuditRecorder audit, DomainEventPublisher events, TransactionRunner tx, Clock clock,
                                  PasswordGenerator generator, Duration defaultInterval) {
        this.accounts = accounts;
        this.store = store;
        this.secrets = secrets;
        this.handles = handles;
        this.operations = operations;
        this.providers = providers;
        this.identities = identities;
        this.guard = guard;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.generator = generator;
        this.defaultInterval = defaultInterval;
    }

    // ------------------------------------------------------------------ vault management

    /** Takes the account under management: the platform sets a new password it alone knows. */
    public VaultedCredentialView manage(CurrentActor actor, UUID accountId, String reason) {
        tx.run(() -> {
            AccountStore.Scoped s = scoped(accountId);
            guard.require(actor, Permissions.CREDENTIAL_MANAGE, AccountService.scope(s), true);
            if (store.find(accountId).filter(v -> v.secretPath() != null).isPresent()) {
                throw IamException.alreadyExists("Vaulted credential for this account");
            }
            startRotation(actor, s, null, "ONBOARD", reason);
        });
        return get(actor, accountId);
    }

    public VaultedCredentialView rotate(CurrentActor actor, UUID accountId, String reason) {
        tx.run(() -> {
            AccountStore.Scoped s = scoped(accountId);
            guard.require(actor, Permissions.CREDENTIAL_MANAGE, AccountService.scope(s), true);
            Vaulted v = store.lock(accountId).filter(x -> x.secretPath() != null).orElseThrow(() -> IamException.notFound("Vaulted credential"));
            if (store.activeCheckout(accountId).isPresent()) {
                throw IamException.validation("accountId", "CHECKED_OUT", "the password is checked out; end the checkout first (it rotates then)");
            }
            startRotation(actor, s, v, "MANUAL", reason);
        });
        return get(actor, accountId);
    }

    /**
     * Writes a new pending version to Vault and sends the rotation to the worker plane. Caller holds the transaction.
     * A rotation already in flight is not duplicated.
     */
    private void startRotation(CurrentActor actor, AccountStore.Scoped s, Vaulted current, String trigger, String reason) {
        Account a = s.account();
        if (current != null && "ROTATING".equals(current.rotationStatus())) {
            throw IamException.invalidTransition("VaultedCredential", "ROTATING", "ROTATE");
        }
        if (a.nativeStatus() == Account.NativeStatus.ABSENT) {
            throw IamException.invalidTransition("Account", "ABSENT", "ROTATE");
        }
        ProviderDirectory.Connection c = providers.connection(a.providerInstanceId())
                .filter(ProviderDirectory.Connection::enabled).filter(x -> x.credentialSecretRef() != null)
                .orElseThrow(() -> IamException.validation("accountId", "NO_CONNECTION", "the server has no enabled connection with a credential"));
        String path = "accounts/" + a.id() + "/password";
        SecretRef pending;
        char[] pw = generator.next();
        try (Secret value = Secret.of(pw)) {
            pending = secrets.write(path, value);
        } finally {
            Arrays.fill(pw, '\0');
        }
        ProviderCommand cmd = new ProviderCommand("ROTATE_PASSWORD", true, c.type(), c.id(), a.targetId(), a.id(), actor.identityId(),
                s.orgUnitPath(), s.environment(), Map.of("account", Map.of("nativeId", a.nativeId(), "name", a.name()),
                "connection", Map.of("endpoint", c.endpoint(), "settings", c.settings())), ROTATION_TIMEOUT, 1,
                "rotate:" + a.id() + ":" + pending.version() + ":" + UUID.randomUUID());
        UUID opId = operations.create(cmd);
        operations.dispatch(opId, cmd, handles.issue(opId, c.type(),
                Map.of("connection", new SecretRef(c.credentialSecretRef()), "new-secret", pending), HANDLE_TTL));
        Instant now = clock.instant();
        if (current == null) {
            store.insert(new Vaulted(a.id(), path, null, pending.value(), "ROTATING", opId, trigger, null, null, null, actor.identityId(), now, 0),
                    now);
        } else {
            save(new Vaulted(a.id(), path, current.currentRef(), pending.value(), "ROTATING", opId, trigger, null, current.lastRotatedAt(),
                    current.rotationIntervalDays(), current.managedBy(), current.createdAt(), current.version()), now);
        }
        audit.record(actor, new AuditEntry("credential.rotation-requested", "account", a.id().toString(), a.targetId(), AuditEntry.Result.SUCCESS,
                reason, a.providerInstanceId(), Map.of("trigger", trigger, "operation", opId.toString(), "vaultVersion",
                String.valueOf(pending.version()))));
    }

    /** Applies a rotation result (synchronous listener, inside the operation-result transaction). */
    public void onCompleted(OperationCompleted c) {
        if (!"ROTATE_PASSWORD".equals(c.operation())) {
            return;
        }
        store.findByOperation(c.operationId()).ifPresent(v -> {
            Instant now = clock.instant();
            Vaulted next;
            String action;
            if ("SUCCESS".equals(c.status())) {
                next = new Vaulted(v.accountId(), v.secretPath(), v.pendingRef(), null, "VERIFIED", null, v.rotationTrigger(), null, now,
                        v.rotationIntervalDays(), v.managedBy(), v.createdAt(), v.version());
                action = "credential.rotated";
            } else if ("FAILED".equals(c.status())) {
                // The target refused the change: the old password (if any) is still valid.
                next = new Vaulted(v.accountId(), v.secretPath(), v.currentRef(), null, "FAILED", null,
                        v.rotationTrigger(), trim(c.errorCode() + ": " + c.errorMessage()), v.lastRotatedAt(), v.rotationIntervalDays(),
                        v.managedBy(), v.createdAt(), v.version());
                action = "credential.rotation-failed";
            } else {
                // UNKNOWN / TIMEOUT / PARTIAL: either value may be in effect; keep both until the next verified rotation.
                next = new Vaulted(v.accountId(), v.secretPath(), v.currentRef(), v.pendingRef(), "UNKNOWN", null, v.rotationTrigger(),
                        trim(c.status() + (c.errorMessage() == null ? "" : ": " + c.errorMessage())), v.lastRotatedAt(),
                        v.rotationIntervalDays(), v.managedBy(), v.createdAt(), v.version());
                action = "credential.rotation-unconfirmed";
            }
            save(next, now);
            audit.record(SYSTEM, new AuditEntry(action, "account", v.accountId().toString(), c.targetId(),
                    "SUCCESS".equals(c.status()) ? AuditEntry.Result.SUCCESS : AuditEntry.Result.FAILURE, next.lastError(), c.providerInstanceId(),
                    Map.of("operation", c.operationId().toString(), "status", String.valueOf(c.status()))));
        });
    }

    /** Scheduled: rotates verified credentials whose interval has passed and that are not checked out. */
    public int rotateDue() {
        int started = 0;
        Instant now = clock.instant();
        for (Vaulted v : tx.readOnly(store::all)) {
            Duration interval = v.rotationIntervalDays() == null ? defaultInterval : Duration.ofDays(v.rotationIntervalDays());
            boolean due = ("VERIFIED".equals(v.rotationStatus()) && v.lastRotatedAt() != null && v.lastRotatedAt().plus(interval).isBefore(now))
                    || "UNKNOWN".equals(v.rotationStatus());
            if (!due) {
                continue;
            }
            try {
                tx.run(() -> {
                    Vaulted locked = store.lock(v.accountId()).orElseThrow();
                    if ("ROTATING".equals(locked.rotationStatus()) || store.activeCheckout(v.accountId()).isPresent()) {
                        return;
                    }
                    startRotation(SYSTEM, scoped(v.accountId()), locked, "SCHEDULED", "rotation interval reached");
                });
                started++;
            } catch (RuntimeException e) {
                // next credential; the failure stays visible as the credential's state
            }
        }
        return started;
    }

    // ------------------------------------------------------------------ checkouts

    public CheckoutView checkoutDirect(CurrentActor actor, UUID accountId, int hours, String reason) {
        if (reason == null || reason.isBlank()) {
            throw IamException.validation("reason", "REQUIRED", "a reason is required to check out a password");
        }
        AccountStore.Scoped s = tx.readOnly(() -> scoped(accountId));
        guard.require(actor, Permissions.CREDENTIAL_MANAGE, AccountService.scope(s), true);
        UUID id = open(actor, accountId, actor.identityId(), null, Duration.ofHours(hours), reason.strip());
        return tx.readOnly(() -> view(store.findCheckout(id).orElseThrow()));
    }

    @Override
    public UUID grant(UUID accountId, UUID identityId, UUID requestId, Duration duration, String reason) {
        return open(SYSTEM, accountId, identityId, requestId, duration, reason);
    }

    private UUID open(CurrentActor actor, UUID accountId, UUID identityId, UUID requestId, Duration duration, String reason) {
        if (duration.isNegative() || duration.isZero() || duration.compareTo(MAX_CHECKOUT) > 0) {
            throw IamException.validation("durationHours", "OUT_OF_RANGE", "a checkout lasts between 1 and 72 hours");
        }
        return tx.inTransaction(() -> {
            Vaulted v = store.lock(accountId).filter(x -> x.secretPath() != null).orElseThrow(() -> IamException.notFound("Vaulted credential"));
            if (v.currentRef() == null) {
                throw IamException.validation("accountId", "NOT_VERIFIED", "the platform does not hold a verified password for this account yet");
            }
            if (store.activeCheckout(accountId).isPresent()) {
                throw IamException.validation("accountId", "CHECKED_OUT", "the password is checked out by someone else");
            }
            if ("ROTATING".equals(v.rotationStatus())) {
                throw IamException.validation("accountId", "ROTATING", "a rotation is in progress; try again in a minute");
            }
            Instant now = clock.instant();
            UUID id = Ids.newId(clock);
            store.insertCheckout(new Checkout(id, accountId, identityId, requestId, reason, now, now.plus(duration), "ACTIVE", null, null, 0, null));
            audit.record(actor, AuditEntry.success("credential.checked-out", "account", accountId, Map.of("checkout", id.toString(),
                    "holder", identityId.toString(), "until", now.plus(duration).toString(), "request", String.valueOf(requestId))));
            return id;
        });
    }

    /** Password for the checkout holder (step-up is enforced at the endpoint). */
    public RevealedCredential reveal(CurrentActor actor, UUID checkoutId) {
        return tx.inTransaction(() -> {
            Checkout c = store.findCheckout(checkoutId).orElseThrow(() -> IamException.notFound("Checkout"));
            if (!c.identityId().equals(actor.identityId())) {
                audit.record(actor, AuditEntry.denied("credential.reveal", "account", c.accountId(), "not the checkout holder"));
                throw IamException.notFound("Checkout");
            }
            Instant now = clock.instant();
            if (!"ACTIVE".equals(c.status()) || !c.notAfter().isAfter(now)) {
                throw IamException.invalidTransition("Checkout", c.status(), "REVEAL");
            }
            Vaulted v = store.find(c.accountId()).orElseThrow(() -> IamException.notFound("Vaulted credential"));
            AccountStore.Scoped s = scoped(c.accountId());
            String password = read(v.currentRef());
            String alternate = "UNKNOWN".equals(v.rotationStatus()) && v.pendingRef() != null ? read(v.pendingRef()) : null;
            store.recordReveal(checkoutId, now);
            audit.record(actor, new AuditEntry("credential.revealed", "account", c.accountId().toString(), s.account().targetId(),
                    AuditEntry.Result.SUCCESS, c.reason(), s.account().providerInstanceId(), Map.of("checkout", checkoutId.toString(),
                    "acr", String.valueOf(actor.acr()))));
            return new RevealedCredential(checkoutId, s.account().name(), s.targetName(), password, alternate, c.notAfter());
        });
    }

    /** Ends a checkout: the holder checks in; a credential manager revokes. Rotates when the password was revealed. */
    public CheckoutView checkIn(CurrentActor actor, UUID checkoutId) {
        tx.run(() -> {
            Checkout c = store.findCheckout(checkoutId).orElseThrow(() -> IamException.notFound("Checkout"));
            boolean holder = c.identityId().equals(actor.identityId());
            if (!holder) {
                guard.require(actor, Permissions.CREDENTIAL_MANAGE, AccountService.scope(scoped(c.accountId())), true);
            }
            end(actor, c, holder ? "CHECKED_IN" : "REVOKED");
        });
        return tx.readOnly(() -> view(store.findCheckout(checkoutId).orElseThrow()));
    }

    /** Scheduled: ends overdue checkouts and rotates their passwords. */
    public int expireCheckouts() {
        int ended = 0;
        for (Checkout c : tx.readOnly(() -> store.overdue(clock.instant()))) {
            try {
                tx.run(() -> end(SYSTEM, c, "EXPIRED"));
                ended++;
            } catch (RuntimeException e) {
                // retried on the next run
            }
        }
        return ended;
    }

    private void end(CurrentActor actor, Checkout c, String status) {
        Instant now = clock.instant();
        if (!store.endCheckout(c.id(), status, actor.identityId(), now)) {
            throw IamException.invalidTransition("Checkout", c.status(), status);
        }
        audit.record(actor, AuditEntry.success("credential.checkout-ended", "account", c.accountId(), Map.of("checkout", c.id().toString(),
                "status", status, "reveals", String.valueOf(c.revealCount()))));
        events.publish(new CredentialCheckoutEnded(c.id(), c.requestId(), c.accountId(), status));
        Checkout fresh = store.findCheckout(c.id()).orElse(c);
        if (fresh.revealCount() > 0) {
            Vaulted v = store.lock(c.accountId()).orElse(null);
            if (v != null && !"ROTATING".equals(v.rotationStatus())) {
                startRotation(SYSTEM, scoped(c.accountId()), v, "CHECK_IN", "password was revealed during checkout " + c.id());
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public List<VaultedCredentialView> list(CurrentActor actor) {
        return tx.readOnly(() -> {
            List<VaultedCredentialView> out = new ArrayList<>();
            for (Vaulted v : store.all()) {
                Optional<AccountStore.Scoped> s = accounts.find(v.accountId());
                if (s.isPresent() && guard.isAllowed(actor, Permissions.ACCOUNT_READ, AccountService.scope(s.get()))) {
                    out.add(view(v, s.get()));
                }
            }
            return out;
        });
    }

    public VaultedCredentialView get(CurrentActor actor, UUID accountId) {
        return tx.readOnly(() -> {
            AccountStore.Scoped s = scoped(accountId);
            guard.require(actor, Permissions.ACCOUNT_READ, AccountService.scope(s), true);
            return view(store.find(accountId).orElseThrow(() -> IamException.notFound("Vaulted credential")), s);
        });
    }

    public List<CheckoutView> myCheckouts(CurrentActor actor) {
        return tx.readOnly(() -> store.checkoutsOf(actor.identityId(), LIST_LIMIT).stream().map(this::view).toList());
    }

    public List<CheckoutView> allCheckouts(CurrentActor actor) {
        guard.require(actor, Permissions.CREDENTIAL_MANAGE, ResourceScope.PLATFORM, false);
        return tx.readOnly(() -> store.recentCheckouts(LIST_LIMIT).stream().map(this::view).toList());
    }

    @Override
    public List<CheckoutTarget> checkoutTargets() {
        return tx.readOnly(() -> store.all().stream().map(v -> target(v).orElse(null)).filter(t -> t != null).toList());
    }

    @Override
    public Optional<CheckoutTarget> checkoutTarget(UUID accountId) {
        return tx.readOnly(() -> store.find(accountId).filter(v -> v.secretPath() != null).flatMap(this::target));
    }

    private Optional<CheckoutTarget> target(Vaulted v) {
        return accounts.find(v.accountId()).map(s -> {
            String why = v.currentRef() == null ? "no verified password yet"
                    : "ROTATING".equals(v.rotationStatus()) ? "rotation in progress"
                    : store.activeCheckout(v.accountId()).isPresent() ? "checked out" : null;
            return new CheckoutTarget(v.accountId(), s.account().name(), s.account().targetId(), s.targetName(), s.providerType(), why == null, why);
        });
    }

    // ------------------------------------------------------------------ helpers

    private VaultedCredentialView view(Vaulted v, AccountStore.Scoped s) {
        Duration interval = v.rotationIntervalDays() == null ? defaultInterval : Duration.ofDays(v.rotationIntervalDays());
        CheckoutView active = store.activeCheckout(v.accountId()).map(this::view).orElse(null);
        return new VaultedCredentialView(v.accountId(), s.account().name(), s.account().targetId(), s.targetName(), s.providerType(),
                s.account().privileged(), v.rotationStatus(), v.rotationTrigger(), v.lastRotatedAt(), v.lastError(), v.rotationOperationId(),
                v.lastRotatedAt() == null ? null : v.lastRotatedAt().plus(interval), active);
    }

    private CheckoutView view(Checkout c) {
        Optional<AccountStore.Scoped> s = accounts.find(c.accountId());
        return new CheckoutView(c.id(), c.accountId(), s.map(x -> x.account().name()).orElse(null), s.map(x -> x.account().targetId()).orElse(null),
                s.map(AccountStore.Scoped::targetName).orElse(null), c.identityId(), name(c.identityId()), c.requestId(), c.reason(), c.startedAt(),
                c.notAfter(), c.status(), c.endedAt(), name(c.endedBy()), c.revealCount(), c.lastRevealedAt());
    }

    private String read(String ref) {
        try (Secret s = secrets.read(new SecretRef(ref))) {
            // vault reveal: only for the active checkout holder after step-up; audited by the caller
            return new String(s.reveal()); // nosemgrep: iam-secret-reveal
        }
    }

    private AccountStore.Scoped scoped(UUID accountId) {
        return accounts.find(accountId).orElseThrow(() -> IamException.notFound("Account"));
    }

    private void save(Vaulted next, Instant now) {
        if (!store.update(next, now)) {
            throw IamException.concurrentModification("Vaulted credential");
        }
    }

    private String name(UUID id) {
        return id == null ? null : identities.find(id).map(IdentitySummary::displayName).orElse(null);
    }

    private static String trim(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
