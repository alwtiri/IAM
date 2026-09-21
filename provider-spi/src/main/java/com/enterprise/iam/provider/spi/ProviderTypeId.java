package com.enterprise.iam.provider.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier of a provider type, e.g. {@code linux}, {@code windows-local}, {@code ad}, {@code vmware},
 * {@code hpe-3par}, {@code database-oracle}. A string rather than an enum so that future providers can be
 * added without changing the SPI (spec §12). The value also names the provider's queue {@code ops.<type>}.
 */
public record ProviderTypeId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    public ProviderTypeId {
        Objects.requireNonNull(value, "value");
        if (value.length() > 48 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid provider type id: " + value);
        }
    }

    public static ProviderTypeId of(String value) {
        return new ProviderTypeId(value);
    }

    /** Name of the RabbitMQ queue carrying lifecycle operations for this type (ADR-0010). */
    public String operationQueue() {
        return "ops." + value;
    }

    /** Name of the queue carrying long-running discovery for this type (ADR-0010). */
    public String discoveryQueue() {
        return "ops.discovery." + value;
    }

    @Override
    public String toString() {
        return value;
    }
}
