package com.enterprise.iam.core.identity.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.domain.Identity;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.shared.api.security.BootstrapAdministratorGrant;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps an authenticated token subject to the platform identity (ADR-0016, SEC3–SEC4). Only {@code sub}, {@code acr},
 * {@code amr} and {@code auth_time} are taken from the token; everything else comes from the platform database.
 */
public class ActorResolver {

    /** Claims extracted by the web layer from an OIDC ID token or an access token. */
    public record AuthenticatedPrincipal(String subject, String acr, List<String> amr, Instant authTime,
                                         CurrentActor.Channel channel, String sourceIp, String preferredUsername,
                                         String givenName, String familyName, String email) {
    }

    private final IdentityStore store;
    private final BootstrapAdministratorGrant bootstrapGrant;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;
    private final String bootstrapSubject;

    public ActorResolver(IdentityStore store, BootstrapAdministratorGrant bootstrapGrant, AuditRecorder audit,
                         TransactionRunner tx, Clock clock, String bootstrapSubject) {
        this.store = store;
        this.bootstrapGrant = bootstrapGrant;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.bootstrapSubject = bootstrapSubject == null || bootstrapSubject.isBlank() ? null : bootstrapSubject.strip();
    }

    /**
     * @throws IamException ACCESS_DENIED if the subject is unknown or the identity is not ACTIVE
     */
    public CurrentActor resolve(AuthenticatedPrincipal p) {
        Optional<IdentityStore.PlatformUserRef> ref = tx.readOnly(() -> store.findBySubject(p.subject()));
        if (ref.isEmpty()) {
            Optional<UUID> bootstrapped = tryBootstrap(p);
            if (bootstrapped.isEmpty()) {
                deny(p, "unknown subject");
            }
            return actor(bootstrapped.get(), p);
        }
        if (!IdentityState.ACTIVE.name().equals(ref.get().state())) {
            deny(p, "identity not active");
        }
        return actor(ref.get().identityId(), p);
    }

    private Optional<UUID> tryBootstrap(AuthenticatedPrincipal p) {
        if (bootstrapSubject == null || !bootstrapSubject.equals(p.subject()) || p.channel() != CurrentActor.Channel.BROWSER_SESSION) {
            return Optional.empty();
        }
        return tx.inTransaction(() -> {
            if (store.platformUserCount() > 0 || !store.markBootstrapCompleted(clock.instant(), p.subject())) {
                return Optional.<UUID>empty();
            }
            Instant now = clock.instant();
            UUID personId = Ids.newId(clock);
            store.insert(new Person(personId, null, null, orDefault(p.givenName(), "Platform"), orDefault(p.familyName(), "Administrator"),
                    null, null, null, null, Person.EmploymentStatus.ACTIVE, null, null, p.email(), null, 0));
            UUID identityId = Ids.newId(clock);
            String username = usernameFrom(p.preferredUsername());
            Identity identity = Identity.create(identityId, personId, IdentityType.EMPLOYEE, username, now, null)
                    .transitionTo(IdentityState.ACTIVE, "platform bootstrap");
            store.insert(identity);
            store.insertPlatformUser(identityId, p.subject(), now);
            bootstrapGrant.grantPlatformAdministrator(identityId);
            audit.record(CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID),
                    AuditEntry.success("platform.bootstrap", "identity", identityId, Map.of("subject", p.subject(), "username", username)));
            return Optional.of(identityId);
        });
    }

    private void deny(AuthenticatedPrincipal p, String reason) {
        tx.run(() -> audit.record(null, new AuditEntry("auth.access.denied", "subject", p.subject(), null,
                AuditEntry.Result.DENIED, reason, null, Map.of("channel", p.channel().name()))));
        throw IamException.accessDenied();
    }

    private static CurrentActor actor(UUID identityId, AuthenticatedPrincipal p) {
        return new CurrentActor(identityId, p.subject(), p.acr(), p.amr(), p.authTime(), p.channel(), p.sourceIp());
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    static String usernameFrom(String preferred) {
        String u = preferred == null ? "" : preferred.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        return Identity.USERNAME.matcher(u).matches() ? u : "platform-admin";
    }
}
