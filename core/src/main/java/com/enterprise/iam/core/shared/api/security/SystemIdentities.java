package com.enterprise.iam.core.shared.api.security;

import java.util.UUID;

/** Well-known seeded identifiers (migration V5). */
public final class SystemIdentities {

    /** Identity used for scheduled jobs and bootstrap; never has a platform login. */
    public static final UUID SYSTEM_IDENTITY_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");

    /** The single organization of this deployment (Phase 0 G-02). */
    public static final UUID DEFAULT_ORGANIZATION_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private SystemIdentities() {
    }
}
