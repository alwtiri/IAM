package com.enterprise.iam.core.target.api;

import com.enterprise.iam.core.shared.api.security.ResourceScope;
import java.util.Optional;
import java.util.UUID;

/** Scope lookup of targets for other modules. */
public interface TargetDirectory {
    Optional<ResourceScope> scopeOf(UUID targetId);
}
