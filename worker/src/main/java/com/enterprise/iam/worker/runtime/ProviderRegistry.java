package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.spi.SpiVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Provider plugins found on the worker's classpath via {@link ServiceLoader} (ADR-0003). A plugin with a different SPI
 * major version is refused; only types in the worker's pool list are served.
 */
public final class ProviderRegistry {

    private static final Logger LOG = Logger.getLogger(ProviderRegistry.class.getName());

    private final Map<String, ProviderFactory> factories = new TreeMap<>();

    public ProviderRegistry(Iterable<ProviderFactory> candidates, List<String> servedTypes) {
        for (ProviderFactory f : candidates) {
            String type = f.descriptor().type().value();
            if (f.descriptor().spiMajor() != SpiVersion.MAJOR) {
                LOG.warning("Ignoring provider " + type + ": SPI major " + f.descriptor().spiMajor() + " != " + SpiVersion.MAJOR);
                continue;
            }
            if (servedTypes.contains(type) && factories.putIfAbsent(type, f) != null) {
                throw new IllegalStateException("Two provider implementations for type " + type);
            }
        }
    }

    public static ProviderRegistry fromClasspath(List<String> servedTypes) {
        return new ProviderRegistry(ServiceLoader.load(ProviderFactory.class), servedTypes);
    }

    public Optional<ProviderFactory> find(String type) {
        return Optional.ofNullable(factories.get(type));
    }

    public Map<String, String> versions() {
        Map<String, String> out = new TreeMap<>();
        factories.forEach((t, f) -> out.put(t, f.descriptor().implementationVersion()));
        return out;
    }
}
