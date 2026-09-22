# Development Environment

## Prerequisites

| Tool | Version | Used for |
|---|---|---|
| Docker Engine + Compose v2 | recent | Running the platform (`deploy/compose`) — sufficient on its own |
| JDK | 21 (Temurin recommended) | Building/testing Java modules outside Docker |
| Gradle | via `./gradlew` (8.14.3) | Java build — do not use a system Gradle |
| Node.js / pnpm | 22 / 10 | `web/` outside Docker |
| Python 3 + PyYAML | 3.10+ | CI helper scripts in `ci/` |

## Repository layout

```text
contracts/             OpenAPI v1 + messaging JSON Schemas (source of truth)
shared-kernel/         plain Java: ErrorCode, ApiError, IamException, Secret, Ids, CorrelationId, Json
provider-spi/          plain Java: Provider SPI v1 + capability model
provider-spi-testkit/  contract checks every provider must pass (+ test fixtures in src/test)
core/                  iam-core Spring Boot modular monolith
web/                   iam-web React + TypeScript + MUI (English/Arabic, RTL)
deploy/compose/        Docker Compose stack (ADR-0009)
ci/                    CI helper scripts and Semgrep rules
docs/                  Phase reports, architecture, security, ADRs, traceability
```

## First start (Phase 2)

```bash
cd deploy/compose
cp .env.example .env                 # adjust IAM_PUBLIC_URL / IAM_BIND_ADDRESS for LAN access
./scripts/generate-dev-secrets.sh    # random dev secrets into ./secrets (git-ignored)
docker compose up -d --build
./scripts/vault-init-dev.sh          # initialise/unseal Vault, KV 'iam/', AppRole for iam-core
./scripts/keycloak-stepup-dev.sh     # tag password/OTP with amr so step-up (MFA) is recognised
docker compose ps                    # all services healthy
```

Sign in:

1. Open `IAM_PUBLIC_URL` (default `http://localhost:8088`) and choose **Sign in**.
2. Log in to Keycloak as `iam-admin` with the password in `secrets/iam_admin_initial_password`. Keycloak forces a new
   password and TOTP enrolment (use any authenticator app).
3. The first login of this user bootstraps the platform: Person, Identity, platform login and a GLOBAL
   `PLATFORM_ADMINISTRATOR` assignment are created once and audited (`platform.bootstrap`, ADR-0016).
4. Actions marked *step-up* (granting roles, registering provider credentials, verifying the audit chain, disabling
   identities) require a recent MFA login; the UI offers **Re-authenticate** when needed.

Useful URLs: Keycloak admin console `…/auth/` (user `kcadmin`, password `secrets/keycloak_admin_password`),
development mailbox (every e-mail the platform sends) `http://localhost:8025`.

## Everyday commands

```bash
./gradlew build                      # compile + unit tests + ArchUnit/Modulith + contract kit + Testcontainers ITs (needs Docker)
cd web && pnpm install && pnpm test  # web tests (vitest)
python3 ci/check-contract-sync.py    # Java enums ↔ OpenAPI / JSON Schema
npx @redocly/cli lint contracts/openapi/iam-core-v1.yaml
```

## Resetting the development stack

`docker compose down` keeps data. `docker compose down -v` deletes **all** data (database, Vault, Keycloak) — only for
development, and Vault must then be initialised again.

## Git workflow (local-first, ADR-0017)

The normative policy is [process/GIT-WORKFLOW-POLICY.md](process/GIT-WORKFLOW-POLICY.md). In short:

1. Work on a branch; build and test locally with `./ci/local-build.sh`: the same `./gradlew build` as CI, inside the pinned JDK image, with Testcontainers support. Run `./ci/local-checks.sh` before committing: project Semgrep rules, contract/enum sync, compose policy, and shellcheck. The registry Semgrep packs, Gitleaks, and Trivy also run when installed.
2. Commit locally with meaningful messages. There is no push after every commit.
3. At a checkpoint (phase gate, milestone, before a risky change) push once with `./scripts/git-push.sh "message" [branch]`. It shows branch and status, blocks secret files, commits, pushes, and prints the PR link. Direct pushes to `main` are refused unless `ALLOW_MAIN=1`.
4. CI validates the PR; it is not the everyday feedback loop.
5. `reset --hard`, `clean -fd`, force-push, `rebase`, and `branch -D` are never used without the owner's explicit approval.

## Phase 2 smoke test (gate condition C2)

Log in with step-up (`$IAM_PUBLIC_URL/oauth2/authorization/keycloak?stepup=1`), copy the `IAM_SESSION` and `XSRF-TOKEN` cookies, and within 5 minutes run from `deploy/compose`:

```bash
IAM_SESSION=... XSRF=... ./scripts/smoke-phase2.sh              # functional, negative, audit, secret-leak, e-mail checks
IAM_SESSION=... XSRF=... ./scripts/smoke-phase2.sh --vault-down # also stops Vault, checks 503 SECRETS_UNAVAILABLE, unseals it again
```

## Rules for contributors

1. Never commit secrets; `.gitignore` and Gitleaks enforce this. Use `deploy/compose/secrets/` locally.
2. Flyway migrations are forward-only and never edited after they have been applied anywhere.
3. Every new endpoint: `@RequiresPermission` (or `@AuthenticatedEndpoint`/`@PublicEndpoint`), object-level check in the
   application service, audit event for every change, OpenAPI entry.
4. Every new provider: extend `ProviderContractTest`; declare capabilities honestly.
5. No Core → extension imports; no cross-module access to non-`api` packages (build fails).
6. Containers: non-root, read-only, `cap_drop: [ALL]`, `no-new-privileges` (CI policy check).
