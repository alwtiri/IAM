# Architecture Decision Records

Format: Context / Decision / Consequences. Numbered sequentially, never renumbered; superseded ADRs stay with status `Superseded by ADR-NNNN`.

- [ADR-0001: Greenfield monorepo and baseline technology stack](ADR-0001-greenfield-monorepo-and-stack.md)
- [ADR-0002: Modular-monolith Core with process-isolated extensions](ADR-0002-modular-monolith-core-with-isolated-extensions.md)
- [ADR-0003: Provider SPI and explicit capability model](ADR-0003-provider-spi-and-capability-model.md)
- [ADR-0004: Keycloak for authentication only; governance stays in the platform](ADR-0004-keycloak-for-authentication-only.md)
- [ADR-0005: Vault as the sole secret store; fail closed](ADR-0005-vault-as-sole-secret-store.md)
- [ADR-0006: Transactional outbox for messaging; Redis as cache only](ADR-0006-transactional-outbox-and-redis-as-cache.md)
- [ADR-0007: Gateway protocol engines: Apache MINA SSHD and Apache Guacamole guacd](ADR-0007-gateway-protocol-engines.md)
- [ADR-0008: Tamper-evident audit and WORM storage for recordings](ADR-0008-tamper-evident-audit-and-worm-recordings.md)
- [ADR-0009: Docker-first deployment, Docker Compose as initial standard](ADR-0009-docker-first-deployment.md)
- [ADR-0010: Worker pools, per-provider queues and bulkheads](ADR-0010-worker-pools-and-provider-isolation.md)
- [ADR-0011: CI platform: GitHub Actions (GitLab CI kept portable)](ADR-0011-ci-platform-github-actions.md)
- [ADR-0012: API versioning, error model, and contract-first OpenAPI](ADR-0012-api-versioning-and-error-model.md)
- [ADR-0013: Policy model and in-process deterministic evaluator](ADR-0013-policy-model-and-evaluator.md)
- [ADR-0014: Spring Boot 4.0 / Spring Framework 7 as backend baseline](ADR-0014-spring-boot-4-baseline.md)
