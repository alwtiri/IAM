package com.enterprise.iam.core.shared.api.security;

import java.util.UUID;

/**
 * Grants the GLOBAL Platform Administrator role during the one-time bootstrap (ADR-0016). Implemented by the
 * authorization module; called by the identity module inside the bootstrap transaction.
 */
@FunctionalInterface
public interface BootstrapAdministratorGrant {
    void grantPlatformAdministrator(UUID identityId);
}
