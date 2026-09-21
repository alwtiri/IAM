package com.enterprise.iam.provider.spi;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Static description of a provider implementation: type, version, SPI version, and capabilities.
 * Capabilities not listed are treated as UNSUPPORTED with a generic explanation.
 */
public final class ProviderDescriptor {

    private final ProviderTypeId type;
    private final String implementationVersion;
    private final int spiMajor;
    private final int spiMinor;
    private final Map<Capability, CapabilityDescriptor> capabilities;

    private ProviderDescriptor(Builder b) {
        this.type = Objects.requireNonNull(b.type, "type");
        this.implementationVersion = Objects.requireNonNull(b.implementationVersion, "implementationVersion");
        this.spiMajor = b.spiMajor;
        this.spiMinor = b.spiMinor;
        this.capabilities = Collections.unmodifiableMap(new EnumMap<>(b.capabilities));
    }

    public static Builder builder(ProviderTypeId type, String implementationVersion) {
        return new Builder(type, implementationVersion);
    }

    public ProviderTypeId type() {
        return type;
    }

    public String implementationVersion() {
        return implementationVersion;
    }

    public int spiMajor() {
        return spiMajor;
    }

    public int spiMinor() {
        return spiMinor;
    }

    /** Declared capabilities (unlisted capabilities are UNSUPPORTED). */
    public Map<Capability, CapabilityDescriptor> capabilities() {
        return capabilities;
    }

    public CapabilityDescriptor capability(Capability capability) {
        return Optional.ofNullable(capabilities.get(capability))
                .orElseGet(() -> CapabilityDescriptor.unsupported(capability,
                        "Not implemented by provider type '" + type + "'"));
    }

    public boolean supports(Capability capability) {
        return capability(capability).isSupported();
    }

    public static final class Builder {
        private final ProviderTypeId type;
        private final String implementationVersion;
        private int spiMajor = SpiVersion.MAJOR;
        private int spiMinor = SpiVersion.MINOR;
        private final Map<Capability, CapabilityDescriptor> capabilities = new EnumMap<>(Capability.class);

        private Builder(ProviderTypeId type, String implementationVersion) {
            this.type = type;
            this.implementationVersion = implementationVersion;
        }

        public Builder spiVersion(int major, int minor) {
            this.spiMajor = major;
            this.spiMinor = minor;
            return this;
        }

        public Builder capability(CapabilityDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (capabilities.putIfAbsent(descriptor.capability(), descriptor) != null) {
                throw new IllegalArgumentException("Capability declared twice: " + descriptor.capability());
            }
            return this;
        }

        public ProviderDescriptor build() {
            return new ProviderDescriptor(this);
        }
    }
}
