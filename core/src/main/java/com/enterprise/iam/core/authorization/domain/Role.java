package com.enterprise.iam.core.authorization.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Named set of permissions. Built-in roles (§19) are seeded and cannot be modified. */
public record Role(UUID id, String code, String name, String description, boolean builtIn, Set<String> permissions) {

    public static final String PLATFORM_ADMINISTRATOR = "PLATFORM_ADMINISTRATOR";

    public Role {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        permissions = Set.copyOf(permissions);
    }
}
