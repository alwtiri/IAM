package com.enterprise.iam.provider.spi;

/**
 * Entry point discovered by the worker runtime via {@link java.util.ServiceLoader}
 * ({@code META-INF/services/com.enterprise.iam.provider.spi.ProviderFactory}).
 */
public interface ProviderFactory {

    ProviderDescriptor descriptor();

    /** Creates a provider bound to one provider instance. Must not perform network I/O. */
    Provider create(ProviderConnection connection);
}
