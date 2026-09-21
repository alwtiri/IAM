package com.enterprise.iam.provider.spi.model;

import com.enterprise.iam.provider.spi.Capability;
import com.enterprise.iam.provider.spi.CapabilityDescriptor;
import java.util.Map;

/**
 * Result of validating a connection: detected target product/version and, where support depends on
 * the version, the effective capabilities (overriding the static descriptor for this instance).
 */
public record ConnectionReport(String product, String productVersion, Map<Capability, CapabilityDescriptor> effectiveCapabilities) {
    public ConnectionReport {
        effectiveCapabilities = effectiveCapabilities == null ? Map.of() : Map.copyOf(effectiveCapabilities);
    }
}
