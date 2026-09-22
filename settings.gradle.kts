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

// Phase 1–3 projects. Later phases add: gateways:*, integrations:*, scheduler.
// A project is included only if its directory exists, so image builds can copy just the projects they need
// (e.g. core/Dockerfile does not copy worker/ or providers/).
listOf(
    "shared-kernel",
    "provider-spi",
    "provider-spi-testkit",
    "core",
    "worker",
    "providers:linux-ssh",
    "providers:active-directory",
    "providers:generic-rest",
    "providers:windows-winrm",
    "providers:postgresql",
).filter { file(it.replace(':', '/')).isDirectory }.forEach { include(it) }
