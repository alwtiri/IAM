package com.enterprise.iam.core.authorization.application;

import com.enterprise.iam.core.authorization.domain.AuthorizationEngine;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.kernel.IamException;
import java.time.Clock;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link AccessGuard} implementation (SEC1–SEC6). Every evaluation error results in DENY (fail closed, spec §4).
 * The SYSTEM identity is allowed everything only when acting through the SYSTEM channel (scheduled jobs).
 */
public class AccessGuardService implements AccessGuard {

    private static final Logger LOG = Logger.getLogger(AccessGuardService.class.getName());

    private final AuthorizationStore store;
    private final OrganizationDirectory org;
    private final Clock clock;

    public AccessGuardService(AuthorizationStore store, OrganizationDirectory org, Clock clock) {
        this.store = store;
        this.org = org;
        this.clock = clock;
    }

    @Override
    public boolean isAllowed(CurrentActor actor, String permission, ResourceScope resource) {
        try {
            return filter(actor, permission).matches(resource);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Authorization evaluation failed; denying", e);
            return false;
        }
    }

    @Override
    public void require(CurrentActor actor, String permission, ResourceScope resource, boolean hideExistence) {
        if (!isAllowed(actor, permission, resource)) {
            // Hide the existence of objects outside the caller's scope (API-GUIDELINES §9.4).
            throw hideExistence ? IamException.notFound("Object") : IamException.accessDenied();
        }
    }

    @Override
    public boolean holdsAnywhere(CurrentActor actor, String permission) {
        try {
            if (isSystem(actor)) {
                return true;
            }
            return actor != null && AuthorizationEngine.holdsAnywhere(grants(actor), permission);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Authorization evaluation failed; denying", e);
            return false;
        }
    }

    @Override
    public ScopeFilter filter(CurrentActor actor, String permission) {
        try {
            if (actor == null) {
                return ScopeFilter.NONE;
            }
            if (isSystem(actor)) {
                return ScopeFilter.GLOBAL;
            }
            return AuthorizationEngine.filter(grants(actor), permission, org::orgUnitPath);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Authorization evaluation failed; denying", e);
            return ScopeFilter.NONE;
        }
    }

    private List<AuthorizationEngine.EffectiveGrant> grants(CurrentActor actor) {
        return store.effectiveGrants(actor.identityId(), clock.instant());
    }

    private static boolean isSystem(CurrentActor actor) {
        return actor != null && actor.channel() == CurrentActor.Channel.SYSTEM
                && SystemIdentities.SYSTEM_IDENTITY_ID.equals(actor.identityId());
    }
}
