package com.enterprise.iam.provider.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderFactory;
import com.enterprise.iam.provider.spi.ProviderConnection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 base class every provider module extends in its test sources:
 *
 * <pre>{@code
 * class LinuxProviderContractTest extends ProviderContractTest {
 *     protected ProviderFactory factory() { return new LinuxProviderFactory(); }
 *     protected ProviderConnection connection() { return ...; }
 * }
 * }</pre>
 *
 * Behavioural tests against real or containerized targets (idempotency, verification, timeouts) are added per
 * provider in Phase 3/5 using Testcontainers; this base class guarantees the structural contract.
 */
public abstract class ProviderContractTest {

    protected abstract ProviderFactory factory();

    protected abstract ProviderConnection connection();

    @Test
    void factoryDescriptorMatchesProviderDescriptor() {
        Provider provider = factory().create(connection());
        assertNotNull(provider, "factory returned null provider");
        assertEquals(factory().descriptor().type(), provider.descriptor().type(), "descriptor type mismatch");
        assertEquals(connection().type(), provider.descriptor().type(), "connection type mismatch");
    }

    @Test
    void structuralContractHolds() {
        List<String> violations = ProviderContractChecks.run(factory().create(connection()));
        assertTrue(violations.isEmpty(), () -> "Provider contract violations:\n - " + String.join("\n - ", violations));
    }
}
