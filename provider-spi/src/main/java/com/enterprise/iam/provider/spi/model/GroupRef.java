package com.enterprise.iam.provider.spi.model;

import java.util.Objects;

/** Reference to a group, role, or equivalent entitlement container on a target. */
public record GroupRef(String nativeId, String name) {
    public GroupRef {
        Objects.requireNonNull(name, "name");
    }
}
