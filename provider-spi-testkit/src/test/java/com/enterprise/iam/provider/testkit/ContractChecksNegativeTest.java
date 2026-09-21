package com.enterprise.iam.provider.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.provider.testkit.fixture.HiddenCapabilityProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The kit must detect declared-but-missing and implemented-but-undeclared capabilities. */
class ContractChecksNegativeTest {

    @Test
    void detectsMissingAndHiddenCapabilities() {
        List<String> violations = ProviderContractChecks.run(new HiddenCapabilityProvider());
        assertTrue(violations.stream().anyMatch(v -> v.startsWith("DISABLE_ACCOUNT") && v.contains("not implemented")),
                () -> "missing-implementation not detected: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.startsWith("UNLOCK_ACCOUNT") && v.contains("hidden capability")),
                () -> "hidden capability not detected: " + violations);
    }
}
