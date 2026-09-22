package com.enterprise.iam.core.identity.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.domain.Identity;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import java.util.Map;
import java.util.UUID;

/**
 * Gives an identity a login: creates the Keycloak account, links it as a platform user and sends the invitation
 * (set password + enrol MFA). Also keeps the Keycloak account's enabled flag in line with the identity lifecycle.
 */
public class PlatformLoginService {

    public record Provisioned(String subject, boolean invitationSent) {
    }

    private final IdentityService identities;
    private final IdentityStore store;
    private final LoginAccountProvisioner provisioner;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;

    public PlatformLoginService(IdentityService identities, IdentityStore store, LoginAccountProvisioner provisioner, AccessGuard guard,
                                AuditRecorder audit, TransactionRunner tx) {
        this.identities = identities;
        this.store = store;
        this.provisioner = provisioner;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
    }

    public boolean available() {
        return provisioner.enabled();
    }

    public Provisioned provision(CurrentActor actor, UUID identityId) {
        record Ctx(Identity identity, Person person) {
        }
        Ctx ctx = tx.readOnly(() -> {
            IdentityStore.Scoped<Identity> i = store.findIdentity(identityId).orElseThrow(() -> IamException.notFound("Identity"));
            guard.require(actor, Permissions.IDENTITY_PLATFORM_USER, PersonService.scopeFromPath(i.orgUnitPath()), true);
            if (i.platformUser()) {
                throw IamException.alreadyExists("Login for this identity");
            }
            Person p = store.findPerson(i.value().personId()).orElseThrow(() -> IamException.notFound("Person")).value();
            return new Ctx(i.value(), p);
        });
        if (ctx.identity().type() == IdentityType.SYSTEM) {
            throw IamException.validation("identityId", "NOT_ALLOWED", "SYSTEM identities cannot log in");
        }
        if (ctx.identity().state() != IdentityState.ACTIVE) {
            throw IamException.validation("identityId", "NOT_ACTIVE", "activate the identity before creating its login");
        }
        if (ctx.person().email() == null || ctx.person().email().isBlank()) {
            throw IamException.validation("email", "REQUIRED", "the person needs an e-mail address to receive the invitation");
        }
        if (!provisioner.enabled()) {
            throw IamException.validation("identityId", "LOGIN_PROVISIONING_DISABLED",
                    "the identity provider admin API is not configured; link an existing login instead");
        }
        String subject = provisioner.createOrFind(new LoginAccountProvisioner.NewLogin(ctx.identity().username(), ctx.person().email(),
                ctx.person().givenName(), ctx.person().familyName()));
        identities.linkPlatformUser(actor, identityId, subject);
        boolean sent;
        try {
            provisioner.sendInvitation(subject);
            sent = true;
        } catch (RuntimeException e) {
            sent = false;
        }
        boolean invitationSent = sent;
        tx.run(() -> audit.record(actor, AuditEntry.success("identity.login-provisioned", "identity", identityId,
                Map.of("subject", subject, "invitationSent", String.valueOf(invitationSent)))));
        return new Provisioned(subject, invitationSent);
    }

    /** Re-sends the invitation (e.g. expired link). */
    public void resendInvitation(CurrentActor actor, UUID identityId) {
        String subject = tx.readOnly(() -> {
            IdentityStore.Scoped<Identity> i = store.findIdentity(identityId).orElseThrow(() -> IamException.notFound("Identity"));
            guard.require(actor, Permissions.IDENTITY_PLATFORM_USER, PersonService.scopeFromPath(i.orgUnitPath()), true);
            return store.subjectOf(identityId).orElseThrow(() -> IamException.validation("identityId", "NO_LOGIN", "the identity has no login yet"));
        });
        provisioner.sendInvitation(subject);
        tx.run(() -> audit.record(actor, AuditEntry.success("identity.login-invitation-sent", "identity", identityId, Map.of("subject", subject))));
    }

    /** Lifecycle hook: suspended/disabled identities cannot sign in at the identity provider either. */
    public void onLifecycleChanged(UUID identityId, String toState) {
        if (!provisioner.enabled()) {
            return;
        }
        tx.readOnly(() -> store.subjectOf(identityId)).ifPresent(subject -> provisioner.setEnabled(subject, "ACTIVE".equals(toState)));
    }
}
