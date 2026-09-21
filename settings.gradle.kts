pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "enterprise-iam-pam"

include(
    "shared-kernel",
    "provider-spi",
    "provider-spi-testkit",
    "core",
)
