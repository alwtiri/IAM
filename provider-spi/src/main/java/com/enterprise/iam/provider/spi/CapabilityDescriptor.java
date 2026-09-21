package com.enterprise.iam.provider.spi;

import java.util.Objects;

/**
 * Declared support for one capability.
 *
 * @param capability   the capability
 * @param status       SUPPORTED, UNSUPPORTED or AGENT_REQUIRED (runtime states are not declarable)
 * @param verification how effects are verified; {@code null} only for non-mutating or unsupported capabilities
 * @param explanation  human-readable reason or limitation (required unless SUPPORTED)
 */
public record CapabilityDescriptor(
        Capability capability,
        CapabilityStatus status,
        VerificationMode verification,
        String explanation) {

    public CapabilityDescriptor {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(status, "status");
        if (status == CapabilityStatus.UNAVAILABLE || status == CapabilityStatus.DEGRADED) {
            throw new IllegalArgumentException(
                    "UNAVAILABLE/DEGRADED are runtime states computed by the Core and cannot be declared");
        }
        if (status != CapabilityStatus.SUPPORTED && (explanation == null || explanation.isBlank())) {
            throw new IllegalArgumentException("An explanation is required for " + status + " " + capability);
        }
    }

    public static CapabilityDescriptor supported(Capability capability, VerificationMode verification) {
        return new CapabilityDescriptor(capability, CapabilityStatus.SUPPORTED, verification, null);
    }

    public static CapabilityDescriptor unsupported(Capability capability, String explanation) {
        return new CapabilityDescriptor(capability, CapabilityStatus.UNSUPPORTED, null, explanation);
    }

    public static CapabilityDescriptor agentRequired(Capability capability, String explanation) {
        return new CapabilityDescriptor(capability, CapabilityStatus.AGENT_REQUIRED, null, explanation);
    }

    public boolean isSupported() {
        return status == CapabilityStatus.SUPPORTED;
    }
}
