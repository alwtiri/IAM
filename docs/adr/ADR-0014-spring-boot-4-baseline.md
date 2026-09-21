# ADR-0014: Spring Boot 4.0 / Spring Framework 7 as backend baseline

- **Status:** Accepted (Phase 1)
- **Date:** 2026-09-21

## Context

Spec §60 names Java 21 + Spring Boot without a version. As of 2026-09 the Spring Boot 3.5 line is past its open-source support window, while Spring Boot 4.0 (GA November 2025) supports Java 17–25 and is the maintained line.

## Decision

Use Java 21 LTS toolchain with Spring Boot 4.0.x, Spring Modulith 2.0.x, Gradle 8.14+ (Kotlin DSL) with a version catalog. Exact patch versions are pinned in `gradle/libs.versions.toml` and updated through a dependency-update bot after CI passes. Provider SPI and shared kernel are plain Java (no Spring dependency) so providers and the contract test kit are framework-independent.

## Consequences

+ Supported framework line for the platform's lifetime start.
- Some third-party libraries may lag Boot 4; each is checked when introduced.
- Upgrade to Java 25 LTS is a later, separate decision.
