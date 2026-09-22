package com.enterprise.iam.core.target.api;

import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import java.util.Optional;
import java.util.UUID;

/** Scope lookup of targets for other modules. */
public interface TargetDirectory {
    Optional<ResourceScope> scopeOf(UUID targetId);

    /**
     * Removes a target from service (soft delete): status DECOMMISSIONED, name freed for reuse, hidden from lists. History
     * (accounts, operations, audit) is kept. Requires {@code target:write} in the target's scope.
     */
    default void decommission(CurrentActor actor, UUID targetId, String reason) {
        throw new UnsupportedOperationException("decommission");
    }
}
