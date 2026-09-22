package com.enterprise.iam.core.identity.api;

import java.util.Optional;
import java.util.UUID;

/** Read access for other modules (authorization, notification). No authorization check: callers enforce their own. */
public interface IdentityDirectory {
    Optional<IdentitySummary> find(UUID identityId);

    /** Exact username match (usernames are unique); used to link discovered accounts to identities. */
    default Optional<IdentitySummary> findByUsername(String username) {
        return Optional.empty();
    }

    /** ACTIVE identity of the person's manager (approvals); empty when no manager or no active identity. */
    default Optional<UUID> managerIdentityOf(UUID identityId) {
        return Optional.empty();
    }
}
