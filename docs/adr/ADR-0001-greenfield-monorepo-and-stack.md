# ADR-0001: Greenfield monorepo and baseline technology stack

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

The repository `alwtiri/IAM` contains only a README (Phase 0 report §1). There is no legacy code, schema, or stack to stay compatible with. Spec §60 names a preferred stack.

## Decision

Adopt the §60 stack without substitution: Java 21 LTS, Spring Boot 3.x, Spring Security, Spring Data JPA/Hibernate, Spring LDAP, OpenAPI; React + TypeScript + MUI (Vite); Keycloak; PostgreSQL 16+ with Flyway; HashiCorp Vault; RabbitMQ; Redis; OpenTelemetry/Prometheus/Grafana; JUnit 5, Testcontainers, Playwright; Docker/Compose; Nginx or Traefik.

Use a single monorepo with a Gradle (Kotlin DSL) multi-project build for Java modules and pnpm for the web app. Layout per Phase 0 report Appendix A.

Additional components not named in §60 (each justified in its own ADR): Spring Modulith + ArchUnit (ADR-0002), Resilience4j (ADR-0003), Apache MINA SSHD and Apache Guacamole guacd (ADR-0007), S3-compatible object storage with Object Lock (ADR-0008).

## Consequences

+ One place for contracts (provider SPI, error model) shared by core, workers, gateways.
+ Atomic changes across modules; one CI pipeline.
- Monorepo CI must be path-aware to stay fast.
- Gradle chosen over Maven for multi-project performance; Maven remains a viable alternative with low migration cost.

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
