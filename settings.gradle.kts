pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// All dependencies come from these repositories only (projects may not declare their own).
// For an air-gapped build, replace them with the internal mirror (Q-05).
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "enterprise-iam-pam"

// Phase 1 projects. Later phases add: worker, providers:*, gateways:*, integrations:*, scheduler.
include(
    "shared-kernel",
    "provider-spi",
    "provider-spi-testkit",
    "core",
)
