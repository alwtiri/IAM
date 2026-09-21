package com.enterprise.iam.provider.spi.model;

import java.util.Map;
import java.util.Objects;

/** A target discovered through a provider (e.g. VMs from vCenter, computers from AD). */
public record DiscoveredTarget(String nativeId, String name, String hostname, String type, Map<String, String> attributes) {
    public DiscoveredTarget {
        Objects.requireNonNull(nativeId, "nativeId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
