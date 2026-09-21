package com.enterprise.iam.core.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Read access for other modules (authorization, notification). No authorization check: callers enforce their own. */
public interface IdentityDirectory {
    Optional<IdentitySummary> find(UUID identityId);
}
