package com.enterprise.iam.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hidden-dependency guard (spec §83, ADR-0002, MODULE-BOUNDARIES.md §3).
 * The Core must never depend on provider implementations, gateways, agents, workers, or integrations.
 */
class ModuleBoundaryTest {

    private static JavaClasses core;
    private static JavaClasses spiAndKernel;

    @BeforeAll
    static void importClasses() {
        core = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.enterprise.iam.core");
        spiAndKernel = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.enterprise.iam.kernel", "com.enterprise.iam.provider.spi");
    }

    @Test
    void coreDoesNotDependOnExtensions() {
        noClasses().that().resideInAPackage("com.enterprise.iam.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.enterprise.iam.providers..",
                        "com.enterprise.iam.gateway..",
                        "com.enterprise.iam.agent..",
                        "com.enterprise.iam.worker..",
                        "com.enterprise.iam.integration..")
                .because("extensions must never become hidden Core dependencies (spec §83)")
                .check(core);
    }

    @Test
    void kernelAndSpiAreFrameworkFree() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..")
                .because("shared-kernel and provider-spi are plain Java (ADR-0003, ADR-0014)")
                .check(spiAndKernel);
    }

    @Test
    void domainPackagesAreFrameworkFree() {
        noClasses().that().resideInAPackage("com.enterprise.iam.core..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "jakarta.persistence..", "jakarta.servlet..")
                .allowEmptyShould(true)
                .because("domain logic must be testable without infrastructure (ARCHITECTURE.md §5)")
                .check(core);
    }

    @Test
    void coreDoesNotUseProviderRuntimeTypes() {
        noClasses().that().resideInAPackage("com.enterprise.iam.core..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.enterprise.iam.provider.spi.Provider")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.enterprise.iam.provider.spi.ProviderFactory")
                .because("providers execute only inside worker pools (ADR-0010); the Core uses capability/result types only")
                .check(core);
    }
}
