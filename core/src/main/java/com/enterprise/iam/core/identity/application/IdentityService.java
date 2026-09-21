package com.enterprise.iam.core.identity.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.identity.api.IdentityView;
import com.enterprise.iam.core.identity.domain.Identity;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity lifecycle use cases (spec §6–§7, F3–F4). Joiner/mover/leaver orchestration is Phase 4; here the
 * individual lifecycle transitions are provided, audited, and published as events.
 */
public class IdentityService implements IdentityDirectory {

    private final IdentityStore store;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final DomainEventPublisher events;
    private final TransactionRunner tx;
    private final Clock clock;

    public IdentityService(IdentityStore store, AccessGuard guard, AuditRecorder audit, DomainEventPublisher events,
                           TransactionRunner tx, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public IdentityView create(CurrentActor actor, UUID personId, IdentityType type, String username, Instant validUntil) {
        return tx.inTransaction(() -> {
            IdentityStore.Scoped<com.enterprise.iam.core.identity.domain.Person> person = store.findPerson(personId)
                    .orElseThrow(() -> IamException.validation("personId", "NOT_FOUND", "person does not exist"));
            guard.require(actor, Permissions.IDENTITY_WRITE, PersonService.scopeFromPath(person.orgUnitPath()), false);
            if (type == IdentityType.SYSTEM) {
                throw IamException.validation("type", "NOT_ALLOWED", "SYSTEM identities are created by the platform only");
            }
            Identity identity = Identity.create(Ids.newId(clock), personId, type, username, clock.instant(), validUntil);
            if (store.usernameExists(identity.username())) {
                throw IamException.alreadyExists("Username " + identity.username());
            }
            store.insert(identity);
            audit.record(actor, AuditEntry.success("identity.created", "identity", identity.id(),
                    Map.of("type", type.name(), "username", identity.username(), "personId", personId.toString())));
            return view(identity, person.displayName(), false);
        });
    }

    public IdentityView transition(CurrentActor actor, UUID identityId, IdentityState target, String reason) {
        return tx.inTransaction(() -> {
            IdentityStore.Scoped<Identity> current = store.findIdentity(identityId)
                    .orElseThrow(() -> IamException.notFound("Identity"));
            guard.require(actor, Permissions.IDENTITY_LIFECYCLE, PersonService.scopeFromPath(current.orgUnitPath()), true);
            if (actor.identityId().equals(identityId)) {
                throw IamException.accessDenied(); // no self-service lifecycle changes of one's own identity
            }
            Identity next = applyTransition(actor, current.value(), target, reason);
            return view(next, current.displayName(), current.platformUser());
        });
    }

    /** Disables identities past their validity end (S11). Returns the number of identities disabled. */
    public int expireDue(int batchSize) {
        CurrentActor system = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);
        return tx.inTransaction(() -> {
            List<Identity> expired = store.findExpired(clock.instant(), batchSize);
            for (Identity i : expired) {
                applyTransition(system, i, IdentityState.DISABLED, "validity expired");
            }
            return expired.size();
        });
    }

    private Identity applyTransition(CurrentActor actor, Identity current, IdentityState target, String reason) {
        Identity next = current.transitionTo(target, reason);
        if (!store.update(next, current.version())) {
            throw IamException.concurrentModification("Identity");
        }
        audit.record(actor, AuditEntry.success("identity." + target.name().toLowerCase(), "identity", current.id(),
                Map.of("from", current.state().name(), "to", target.name(), "reason", reason == null ? "" : reason)));
        events.publish(new IdentityLifecycleChanged(current.id(), current.state().name(), target.name(), reason, actor.identityId()));
        return new Identity(next.id(), next.personId(), next.type(), next.username(), next.state(), next.stateReason(),
                next.validFrom(), next.validUntil(), current.version() + 1);
    }

    public void linkPlatformUser(CurrentActor actor, UUID identityId, String subject) {
        tx.run(() -> {
            IdentityStore.Scoped<Identity> current = store.findIdentity(identityId).orElseThrow(() -> IamException.notFound("Identity"));
            guard.require(actor, Permissions.IDENTITY_PLATFORM_USER, PersonService.scopeFromPath(current.orgUnitPath()), true);
            if (subject == null || subject.isBlank() || subject.length() > 255) {
                throw IamException.validation("subject", "INVALID", "subject is required (max 255 characters)");
            }
            if (current.value().type() == IdentityType.SYSTEM) {
                throw IamException.validation("identityId", "NOT_ALLOWED", "SYSTEM identities cannot log in");
            }
            if (store.hasPlatformUser(identityId)) {
                throw IamException.alreadyExists("Platform user for this identity");
            }
            if (store.subjectExists(subject)) {
                throw IamException.alreadyExists("Platform user for this subject");
            }
            store.insertPlatformUser(identityId, subject, clock.instant());
            audit.record(actor, AuditEntry.success("identity.platform-user.linked", "identity", identityId, Map.of("subject", subject)));
        });
    }

    public IdentityView get(CurrentActor actor, UUID id) {
        IdentityStore.Scoped<Identity> i = tx.readOnly(() -> store.findIdentity(id)).orElseThrow(() -> IamException.notFound("Identity"));
        guard.require(actor, Permissions.IDENTITY_READ, PersonService.scopeFromPath(i.orgUnitPath()), true);
        return view(i.value(), i.displayName(), i.platformUser());
    }

    public PageResult<IdentityView> list(CurrentActor actor, UUID personId, String state, PageRequest page) {
        var filter = guard.filter(actor, Permissions.IDENTITY_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.listIdentities(filter, personId, state, page), page.limit(),
                s -> s.value().id())).map(s -> view(s.value(), s.displayName(), s.platformUser()));
    }

    @Override
    public Optional<IdentitySummary> find(UUID identityId) {
        return store.summary(identityId);
    }

    static IdentityView view(Identity i, String displayName, boolean platformUser) {
        return new IdentityView(i.id(), i.personId(), displayName, i.type().name(), i.username(), i.state().name(), i.stateReason(),
                i.validFrom(), i.validUntil(), platformUser, i.version());
    }
}
