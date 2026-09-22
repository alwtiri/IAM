package com.enterprise.iam.core.provider.application;

import com.enterprise.iam.core.provider.api.ProviderBindingView;
import com.enterprise.iam.core.provider.api.ProviderDirectory;
import com.enterprise.iam.core.target.api.TargetDirectory;
import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.provider.api.ProviderInstanceView;
import com.enterprise.iam.core.provider.domain.ProviderInstance;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.Capability;
import com.enterprise.iam.provider.spi.CapabilityStatus;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import com.enterprise.iam.provider.spi.SpiVersion;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provider instance registry (spec §11, G3). Credentials are written to Vault <em>before</em> the metadata row, and the
 * Vault entry is destroyed again if the database write fails, so neither a dangling reference nor an orphaned secret
 * is left behind. Vault unavailable → {@code SECRETS_UNAVAILABLE}; nothing is persisted.
 */
public class ProviderRegistryService implements ProviderDirectory {

    private static final Logger LOG = Logger.getLogger(ProviderRegistryService.class.getName());

    public record RegisterCommand(String type, String name, String endpoint, Map<String, String> settings, Secret credential) {
    }

    /** Connection change: new endpoint/settings; a new credential (optional) becomes a new Vault version. */
    public record UpdateCommand(String endpoint, Map<String, String> settings, Secret credential) {
    }

    public record CapabilityCatalog(String spiVersion, List<String> capabilities, List<String> statuses) {
    }

    private final ProviderStore store;
    private final SecretStore secrets;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;
    private final TargetDirectory targets;

    public ProviderRegistryService(ProviderStore store, SecretStore secrets, AccessGuard guard, AuditRecorder audit,
                                   TransactionRunner tx, Clock clock) {
        this(store, secrets, guard, audit, tx, clock, targetId -> Optional.empty());
    }

    public ProviderRegistryService(ProviderStore store, SecretStore secrets, AccessGuard guard, AuditRecorder audit,
                                   TransactionRunner tx, Clock clock, TargetDirectory targets) {
        this.targets = targets;
        this.store = store;
        this.secrets = secrets;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public ProviderInstanceView register(CurrentActor actor, RegisterCommand cmd) {
        ProviderTypeId type;
        try {
            type = ProviderTypeId.of(cmd.type());
        } catch (RuntimeException e) {
            throw IamException.validation("type", "INVALID", "invalid provider type");
        }
        UUID id = Ids.newId(clock);
        guard.require(actor, Permissions.PROVIDER_WRITE, new ResourceScope(null, null, type.value(), null, null), false);
        // validate metadata before touching Vault
        new ProviderInstance(id, type, cmd.name(), cmd.endpoint(), cmd.settings(), null, true, 0);
        if (tx.readOnly(() -> store.nameExists(cmd.name()))) {
            throw IamException.alreadyExists("Provider instance " + cmd.name());
        }
        SecretRef ref = cmd.credential() == null ? null : secrets.write("providers/" + id + "/connection", cmd.credential());
        try {
            ProviderInstance p = new ProviderInstance(id, type, cmd.name(), cmd.endpoint(), cmd.settings(),
                    ref == null ? null : ref.value(), true, 0);
            return tx.inTransaction(() -> {
                store.insert(p);
                audit.record(actor, new AuditEntry("provider-instance.registered", "provider-instance", id.toString(), null,
                        AuditEntry.Result.SUCCESS, null, id, Map.of("type", type.value(), "name", p.name(),
                        "credentialStored", String.valueOf(ref != null))));
                return store.view(id).orElseThrow();
            });
        } catch (RuntimeException e) {
            if (ref != null) {
                try {
                    secrets.destroy(ref);
                } catch (RuntimeException cleanup) {
                    LOG.log(Level.SEVERE, "Could not remove Vault entry after failed registration; manual cleanup of providers/"
                            + id + " required", cleanup);
                }
            }
            throw e;
        }
    }

    public ProviderInstanceView setEnabled(CurrentActor actor, UUID id, boolean enabled) {
        return tx.inTransaction(() -> {
            ProviderInstance p = store.find(id).orElseThrow(() -> IamException.notFound("Provider instance"));
            guard.require(actor, Permissions.PROVIDER_WRITE, scope(p), true);
            if (!store.setEnabled(id, enabled, p.version())) {
                throw IamException.concurrentModification("Provider instance");
            }
            audit.record(actor, new AuditEntry(enabled ? "provider-instance.enabled" : "provider-instance.disabled", "provider-instance",
                    id.toString(), null, AuditEntry.Result.SUCCESS, null, id, Map.of()));
            return store.view(id).orElseThrow();
        });
    }

    public ProviderInstanceView update(CurrentActor actor, UUID id, UpdateCommand cmd) {
        ProviderInstance p = tx.readOnly(() -> store.find(id)).orElseThrow(() -> IamException.notFound("Provider instance"));
        guard.require(actor, Permissions.PROVIDER_WRITE, scope(p), true);
        // validate metadata before touching Vault
        new ProviderInstance(id, p.type(), p.name(), cmd.endpoint(), cmd.settings(), p.credentialSecretRef(), p.enabled(), p.version());
        SecretRef ref = cmd.credential() == null ? null : secrets.write("providers/" + id + "/connection", cmd.credential());
        String credentialRef = ref == null ? p.credentialSecretRef() : ref.value();
        return tx.inTransaction(() -> {
            ProviderInstance next = new ProviderInstance(id, p.type(), p.name(), cmd.endpoint(), cmd.settings(), credentialRef, p.enabled(),
                    p.version());
            if (!store.update(next, p.version())) {
                throw IamException.concurrentModification("Provider instance");
            }
            audit.record(actor, new AuditEntry("provider-instance.updated", "provider-instance", id.toString(), null, AuditEntry.Result.SUCCESS,
                    null, id, Map.of("credentialChanged", String.valueOf(ref != null))));
            return store.view(id).orElseThrow();
        });
    }

    /** Removes a connection from service: unbinds it from every target and disables it (kept for history and audit). */
    public void retire(CurrentActor actor, UUID id) {
        tx.run(() -> {
            ProviderInstance p = store.find(id).orElseThrow(() -> IamException.notFound("Provider instance"));
            guard.require(actor, Permissions.PROVIDER_WRITE, scope(p), true);
            for (UUID target : store.targetsOf(id)) {
                store.unbind(target, id);
            }
            if (p.enabled() && !store.setEnabled(id, false, p.version())) {
                throw IamException.concurrentModification("Provider instance");
            }
            audit.record(actor, new AuditEntry("provider-instance.retired", "provider-instance", id.toString(), null, AuditEntry.Result.SUCCESS,
                    null, id, Map.of("name", p.name())));
        });
    }

    /**
     * Deletes a server/database from service: decommissions the target and retires the connections that served only it,
     * in one transaction. Accounts, operations and audit history are kept.
     */
    public void deleteTarget(CurrentActor actor, UUID targetId, String reason) {
        tx.run(() -> {
            targets.decommission(actor, targetId, reason);
            for (ProviderBindingView b : store.bindings(targetId)) {
                store.unbind(targetId, b.providerInstanceId());
                if (store.targetsOf(b.providerInstanceId()).isEmpty()) {
                    store.find(b.providerInstanceId()).filter(ProviderInstance::enabled).ifPresent(p -> store.setEnabled(p.id(), false, p.version()));
                }
            }
        });
    }

    public ProviderInstanceView get(CurrentActor actor, UUID id) {
        ProviderInstance p = tx.readOnly(() -> store.find(id)).orElseThrow(() -> IamException.notFound("Provider instance"));
        guard.require(actor, Permissions.PROVIDER_READ, scope(p), true);
        return tx.readOnly(() -> store.view(id)).orElseThrow();
    }

    public PageResult<ProviderInstanceView> list(CurrentActor actor, String type, PageRequest page) {
        var filter = guard.filter(actor, Permissions.PROVIDER_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.list(filter, type, page), page.limit(), ProviderInstanceView::id));
    }

    public CapabilityCatalog catalog(CurrentActor actor) {
        if (!guard.holdsAnywhere(actor, Permissions.SYSTEM_READ)) {
            throw IamException.accessDenied();
        }
        return new CapabilityCatalog(SpiVersion.current(), Arrays.stream(Capability.values()).map(Enum::name).toList(),
                Arrays.stream(CapabilityStatus.values()).map(Enum::name).toList());
    }

    // ------------------------------------------------------------------ target bindings (Phase 3)

    /** Binds a provider instance to a target; both the instance and the target must be in the actor's write scope. */
    public List<ProviderBindingView> bind(CurrentActor actor, UUID targetId, UUID providerInstanceId, String channel) {
        String ch = channel == null || channel.isBlank() ? "accounts" : channel;
        if (!ch.matches("^[a-z][a-z-]{1,31}$")) {
            throw IamException.validation("channel", "INVALID", "lower-case channel name such as accounts");
        }
        return tx.inTransaction(() -> {
            ProviderInstance p = store.find(providerInstanceId).orElseThrow(() -> IamException.notFound("Provider instance"));
            guard.require(actor, Permissions.PROVIDER_WRITE, scope(p), true);
            ResourceScope target = targets.scopeOf(targetId).orElseThrow(() -> IamException.notFound("Target"));
            guard.require(actor, Permissions.TARGET_WRITE, target, true);
            if (store.bind(targetId, providerInstanceId, ch)) {
                audit.record(actor, new AuditEntry("provider-instance.bound", "provider-instance", providerInstanceId.toString(), targetId,
                        AuditEntry.Result.SUCCESS, null, providerInstanceId, Map.of("channel", ch, "type", p.type().value())));
            }
            return store.bindings(targetId);
        });
    }

    public List<ProviderBindingView> unbind(CurrentActor actor, UUID targetId, UUID providerInstanceId) {
        return tx.inTransaction(() -> {
            ProviderInstance p = store.find(providerInstanceId).orElseThrow(() -> IamException.notFound("Provider instance"));
            guard.require(actor, Permissions.PROVIDER_WRITE, scope(p), true);
            ResourceScope target = targets.scopeOf(targetId).orElseThrow(() -> IamException.notFound("Target"));
            guard.require(actor, Permissions.TARGET_WRITE, target, true);
            if (store.unbind(targetId, providerInstanceId)) {
                audit.record(actor, new AuditEntry("provider-instance.unbound", "provider-instance", providerInstanceId.toString(), targetId,
                        AuditEntry.Result.SUCCESS, null, providerInstanceId, Map.of()));
            }
            return store.bindings(targetId);
        });
    }

    public List<ProviderBindingView> bindings(CurrentActor actor, UUID targetId) {
        ResourceScope target = targets.scopeOf(targetId).orElseThrow(() -> IamException.notFound("Target"));
        guard.require(actor, Permissions.TARGET_READ, target, true);
        return tx.readOnly(() -> store.bindings(targetId));
    }

    @Override
    public Optional<Connection> connection(UUID providerInstanceId) {
        return store.find(providerInstanceId).map(p -> new Connection(p.id(), p.type().value(), p.endpoint(), p.settings(),
                p.credentialSecretRef(), p.enabled()));
    }

    @Override
    public List<ProviderBindingView> allBindings() {
        return tx.readOnly(store::allBindings);
    }

    @Override
    public boolean isBound(UUID targetId, UUID providerInstanceId) {
        return store.isBound(targetId, providerInstanceId);
    }

    static ResourceScope scope(ProviderInstance p) {
        return new ResourceScope(null, null, p.type().value(), p.id(), null);
    }
}
