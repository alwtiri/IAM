# Development Environment

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| JDK | 21 (Temurin recommended) | `shared-kernel`, `provider-spi`, `provider-spi-testkit`, `core` |
| Gradle | via `./gradlew` (8.14.3) | Java build — do not use a system Gradle |
| Node.js / pnpm | 22 / 10 | `web/` |
| Docker Engine + Compose v2 | recent | Local stack (`deploy/compose`) |
| Python 3 | 3.10+ | CI helper scripts in `ci/` |

## Repository layout

```text
contracts/             OpenAPI v1 + messaging JSON Schemas (source of truth)
shared-kernel/         plain Java: ErrorCode, ApiError, Secret, Ids, CorrelationId
provider-spi/          plain Java: Provider SPI v1 + capability model
provider-spi-testkit/  contract checks every provider must pass (+ test fixtures in src/test)
core/                  iam-core Spring Boot modular monolith (Phase 1: skeleton + boundaries + Flyway V1)
web/                   iam-web React + TypeScript + MUI (Phase 1: shell with en/ar + RTL)
deploy/compose/        Docker Compose dev stack (ADR-0009)
ci/                    CI helper scripts and Semgrep rules
docs/                  Phase reports, architecture, security, ADRs, traceability
```

## Everyday commands

```bash
./gradlew build                      # compile + unit tests + ArchUnit/Modulith + provider contract kit
cd web && pnpm install && pnpm test  # web tests (vitest)
python3 ci/check-contract-sync.py    # Java enums ↔ OpenAPI / JSON Schema
npx @redocly/cli lint contracts/openapi/iam-core-v1.yaml
```

## Local stack

```bash
cd deploy/compose
./scripts/generate-dev-secrets.sh    # random dev secrets into ./secrets (git-ignored)
docker compose up -d --build
./scripts/vault-init-dev.sh          # dev-only init/unseal of Vault
open http://localhost:8088           # iam-web through iam-proxy
```

`iam-core` in Phase 1 denies every request except health probes; authentication arrives in Phase 2. The Flyway baseline creates the module schemas at startup. Keycloak's admin console is at `http://localhost:8088/auth/` (user `kcadmin`, password in `secrets/keycloak_admin_password`).

## Rules for contributors

1. Never commit secrets; `.gitignore` and Gitleaks enforce this. Use `deploy/compose/secrets/` locally.
2. Flyway migrations are forward-only and never edited after merge.
3. Every new endpoint: permission + scope resolver, audit event, OpenAPI entry first.
4. Every new provider: extend `ProviderContractTest`; declare capabilities honestly.
5. No Core → extension imports (build fails).
6. Containers: non-root, read-only, `cap_drop: [ALL]`, `no-new-privileges` (CI policy check).
