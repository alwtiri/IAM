package com.enterprise.iam.core.identity.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Identity lifecycle (DOMAIN-MODEL §3). */
public enum IdentityState {
    PENDING, ACTIVE, SUSPENDED, DISABLED, ARCHIVED;

    private static final Map<IdentityState, Set<IdentityState>> ALLOWED = Map.of(
            PENDING, EnumSet.of(ACTIVE, DISABLED),
            ACTIVE, EnumSet.of(SUSPENDED, DISABLED),
            SUSPENDED, EnumSet.of(ACTIVE, DISABLED),
            DISABLED, EnumSet.of(ARCHIVED),
            ARCHIVED, EnumSet.noneOf(IdentityState.class));

    public boolean canTransitionTo(IdentityState next) {
        return ALLOWED.get(this).contains(next);
    }
}
