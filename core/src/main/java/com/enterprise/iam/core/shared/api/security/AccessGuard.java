package com.enterprise.iam.core.shared.api.security;

/**
 * Server-side authorization entry point (spec §19, SEC1–SEC6). Implemented by the authorization module.
 * Every implementation must deny on any internal error.
 */
public interface AccessGuard {

    /** @return true if the actor holds {@code permission} in a scope covering {@code resource} */
    boolean isAllowed(CurrentActor actor, String permission, ResourceScope resource);

    /**
     * Object-level check.
     *
     * @throws com.enterprise.iam.kernel.IamException NOT_FOUND when denied and {@code hideExistence} (default for reads),
     *                                                otherwise ACCESS_DENIED
     */
    void require(CurrentActor actor, String permission, ResourceScope resource, boolean hideExistence);

    /** @return true if the actor holds {@code permission} in at least one scope (coarse endpoint check) */
    boolean holdsAnywhere(CurrentActor actor, String permission);

    /** Visibility filter for list queries. */
    ScopeFilter filter(CurrentActor actor, String permission);
}
