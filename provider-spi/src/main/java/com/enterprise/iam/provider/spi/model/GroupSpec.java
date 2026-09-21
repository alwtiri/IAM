package com.enterprise.iam.provider.spi.model;

import java.util.Map;
import java.util.Objects;

/** Desired properties of a group to create or modify. */
public record GroupSpec(String name, String description, Map<String, String> attributes) {
    public GroupSpec {
        Objects.requireNonNull(name, "name");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
