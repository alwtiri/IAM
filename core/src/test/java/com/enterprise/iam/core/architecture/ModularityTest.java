package com.enterprise.iam.core.architecture;

import com.enterprise.iam.core.IamCoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module graph declared in each module's package-info (MODULE-BOUNDARIES.md §2):
 * no cycles, no access to other modules' internal packages, only allowed dependencies.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(IamCoreApplication.class);

    @Test
    void moduleStructureIsValid() {
        modules.verify();
    }
}
