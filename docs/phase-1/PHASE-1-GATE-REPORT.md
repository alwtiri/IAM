# Phase 1 Gate Report — Architecture & Domain Model

| Field | Value |
|---|---|
| Phase | 1 — Architecture & Domain Model |
| Date | 2026-09-21 |
| Baseline | Master Prompt §0–§85 · Phase 0 Report (approved) with Addendum G (gate clarifications G1–G12) |
| Status | **APPROVED 2026-09-21** (owner). C1: local run met 2026-09-22; the CI part is carried into Phase 2 condition C2 |
| Decision requested | Approve Phase 1 and authorize Phase 2 — IAM Foundation, subject to condition C1 (§6) |

---

## 1. Summary

Phase 1 delivers the architecture baseline, domain and data models, module boundaries, API and messaging contracts, the Provider SPI v1 with its capability model and contract test kit, the security architecture and Core threat model, and the Docker-first, CI/CD, development-environment, and migration foundations.

Following G10, no production business functions were built: no gateways, provider implementations, agents, database proxies, or governance logic. The only executable provider code is a test fixture that lives in test sources and exists to exercise the contract kit.

The Phase 0 gate clarifications are recorded in Phase 0 Addendum G, ADR-0009 and ADR-0010 (new), and the amended ADR-0002/0003/0004/0005/0008. They are carried into every Phase 1 artifact. Nothing was descoped: all 84 requirement sections remain in the traceability matrix (G11).

## 2. Deliverables

| # | Deliverable (G10 list) | Artifact(s) | State |
|---|---|---|---|
| D1 | Architecture | [`docs/architecture/ARCHITECTURE.md`](../architecture/ARCHITECTURE.md): principles, C4 context/containers, Core structure, cross-cutting mechanisms, normative dependency behaviour, **§83 ten-question assessment for every component**, agent/gateway/DB-governance-vs-PAM summaries | Complete |
| D2 | Domain model | [`DOMAIN-MODEL.md`](../architecture/DOMAIN-MODEL.md): bounded contexts, ER model, aggregates, invariants, state machines (Identity, Account governance, Access Request, Operation), SoD/policy/risk/grant/session/audit/agent concepts | Complete |
| D3 | Database migration foundation + logical data model | [`DATA-MODEL.md`](../architecture/DATA-MODEL.md) (conventions, key tables per schema, phase allocation); `core/.../db/migration/V1__baseline_module_schemas.sql` (module schemas + role grants, audit INSERT/SELECT-only); `deploy/compose/postgres/init/01-roles.sh` (owner/app/readonly roles, passwords from secrets) | Complete |
| D4 | Module boundaries | [`MODULE-BOUNDARIES.md`](../architecture/MODULE-BOUNDARIES.md); 20 Core modules declared via Spring Modulith `@ApplicationModule(allowedDependencies)` + `@NamedInterface("api")`; `ModularityTest`, `ModuleBoundaryTest` (ArchUnit) | Complete (tests run in CI — see §4) |
| D5 | API contracts | [`API-GUIDELINES.md`](../architecture/API-GUIDELINES.md); `contracts/openapi/iam-core-v1.yaml` (error model, pagination, operations, capability catalog, health with affected functionality); ADR-0012 | Complete |
| D6 | Messaging contracts | `contracts/messaging/operation-command.schema.json`, `operation-result.schema.json` (no secret fields; SUCCEEDED ⇒ no error) | Complete |
| D7 | Provider SPI + capability model | `provider-spi/` (plain Java): `Provider` with honest `UNSUPPORTED_CAPABILITY` defaults for all 22 §11 operations, `Capability`, `CapabilityStatus` (SUPPORTED / UNSUPPORTED / UNAVAILABLE / DEGRADED / AGENT_REQUIRED), `OperationResult` (mutating SUCCEEDED requires `Verification`), `CredentialHandle`/`CredentialResolver` (fail closed), `ProviderTypeId` → isolated queues | Complete, tested |
| D8 | Provider contract test kit | `provider-spi-testkit/`: `ProviderContractChecks` (declared-but-missing, implemented-but-undeclared, unsupported-must-return-UNSUPPORTED) + JUnit base class; compliant and deliberately broken fixtures | Complete, tested |
| D9 | Security architecture | [`SECURITY-ARCHITECTURE.md`](../security/SECURITY-ARCHITECTURE.md) (trust zones, authN/authZ, secrets, crypto, audit integrity, data protection, containers, supply chain); [`THREAT-MODEL-CORE.md`](../security/THREAT-MODEL-CORE.md) (22 STRIDE threats with mitigations and verification) | Complete |
| D10 | Docker Compose foundation | [`DEPLOYMENT.md`](../architecture/DEPLOYMENT.md); `deploy/compose/compose.yaml` (proxy, web, core, PostgreSQL, Keycloak + own DB, Vault/Raft, RabbitMQ, cache), zone networks, named volumes, Compose secrets, hardening anchor; `core/Dockerfile`, `web/Dockerfile` (multi-stage, non-root) | Complete (config validated; not started — see §4) |
| D11 | CI/CD foundation | `.github/workflows/ci.yml` (backend, web, contracts, compose policy, Gitleaks, Semgrep, Trivy fs + images), `scheduled-security.yml` (OWASP Dependency-Check), `ci/check-contract-sync.py`, `ci/check-compose-policy.py`, `ci/semgrep/iam-rules.yaml`, `.gitleaks.toml`, `.redocly.yaml`; ADR-0011 | Complete (not yet executed on GitHub — see §4) |
| D12 | Development environment | [`docs/DEVELOPMENT.md`](../DEVELOPMENT.md), Gradle wrapper + version catalog, `web/` shell (React 19 + TS + MUI 7, English/Arabic with RTL), `.editorconfig`, `.gitignore` | Complete |
| D13 | Decisions | ADR-0009 Docker-first · ADR-0010 worker pools & provider isolation · ADR-0011 CI platform · ADR-0012 API versioning & error model · ADR-0013 policy model & evaluator · ADR-0014 Spring Boot 4 baseline; ADR-0001…0008 accepted (0002/0003/0004/0005/0008 amended) | Complete |
| D14 | Worker isolation design (G4) | [`WORKER-ISOLATION.md`](../architecture/WORKER-ISOLATION.md): per-provider queues, pools per provider group, bulkheads, breakers, back-pressure, DLQ, idempotency, required failure tests | Complete |
| D15 | Traceability | `docs/traceability/rtm.csv`: 84 requirements; 77 `DESIGNED`, 1 `ACCEPTED` (§77), 6 process requirements `PLANNED` (§75, §76, §78, §79, §81, §84 — these apply throughout the project) | Updated |

## 3. How the gate clarifications are carried forward

| Clarification | Where it is enforced |
|---|---|
| G1 Docker-first | ADR-0009; one image per deployable; Compose `x-hardening` anchor; `ci/check-compose-policy.py` fails the build on privileged containers, Docker socket mounts, host namespaces, root users, missing `cap_drop: ALL` / `no-new-privileges`, or a writable root filesystem without a documented exception (Keycloak dev and RabbitMQ are the only exceptions, each commented) |
| G2 Keycloak = authentication only | ADR-0004 amendment; Keycloak on its own database; DOMAIN-MODEL §3–§4 (roles and scopes only in the platform DB); threat T15 |
| G3 Vault boundary | ADR-0005 amendment; ARCHITECTURE §7; `CredentialResolver.SecretsUnavailableException` → `OperationResult.secretsUnavailable` (tested); Semgrep rule `iam-no-plaintext-secret-cache` |
| G4 Worker isolation | ADR-0010; WORKER-ISOLATION.md; `ProviderTypeId.operationQueue()/discoveryQueue()` (tested) |
| G5 Provider isolation, verified success | `CapabilityStatus`; `OperationResult` invariant (tested); contract kit |
| G6 Agentless by default | ARCHITECTURE §9; `AGENT_REQUIRED` status; no agent on any Core path |
| G7 Gateway isolation incl. API gateway | ARCHITECTURE §4, §8, §10; one network per gateway (DEPLOYMENT §3) |
| G8 Account Management is Core | `account` Core module; DOMAIN-MODEL §6 governance state machine; SPI package rules (providers never write governance state) |
| G9 DB governance ≠ DB PAM | ARCHITECTURE §11; separate deployables and phases; per-engine capability descriptors |
| G10 Phase 1 scope | Only foundations and fixtures (in test sources); later-phase services not declared in Compose |
| G11 No scope reduction | RTM keeps all 84 sections |

## 4. Verification evidence

### 4.1 Verified in this phase

| Check | Result |
|---|---|
| `shared-kernel` + `provider-spi` main sources, `javac --release 21 -Xlint:all -Werror` | Pass (0 warnings) |
| Unit and contract tests: `SecretTest` (5), `IdsAndCorrelationTest` (4), `OperationResultInvariantsTest` (10), `ContractChecksNegativeTest` (1), `InMemoryTestProviderContractTest` (2) | **22 / 22 pass.** Run with a minimal local JUnit-API runner because JUnit could not be downloaded here (§4.2). The same sources run under real JUnit 5 in CI |
| Contract kit catches non-compliant providers | Pass: detects both a declared-but-missing capability and an implemented-but-undeclared one |
| OpenAPI v1 lint (Redocly, `recommended` + 4xx rule) | Pass, 0 warnings |
| Java ↔ contract enum sync (`ci/check-contract-sync.py`) | Pass: 19 error codes, 22 operations, 5 capability statuses |
| Core module declarations compile (annotation stubs), module graph acyclic, matches MODULE-BOUNDARIES.md | Pass: 20 modules, no cycles, no unknown or undocumented dependencies |
| Flyway V1 and role bootstrap SQL parsed with the PostgreSQL parser (pglast), PL/pgSQL block included | Pass |
| `docker compose config` | Pass |
| Compose hardening policy on the real stack | Pass: 9 services. A negative test with privileged, root user and Docker socket mount is rejected |
| Project Semgrep rules on the repository | Pass: 0 findings. A negative sample triggers all three blocking rules |
| Web: `tsc` strict, vitest (3 tests: title, Arabic/RTL switch, locale completeness), production build | Pass |
| All Mermaid diagrams (Phase 0 + Phase 1 documents) render | Pass |
| Markdown cross-links | Pass (after this report was added) |

### 4.2 Not verifiable in this environment — must pass in CI before the gate closes

The build sandbox's egress policy blocks Maven Central, the Gradle plugin portal and distribution server, and Docker Hub. It also has no Docker daemon. As a result, the following have **not been executed yet**:

1. `./gradlew build` for the `core` project: Spring Boot 4.0.1 / Spring Modulith 2.0.1 resolution, `SecurityConfiguration` compilation, and the `ModularityTest` and `ModuleBoundaryTest` runs. `core` compiled only against annotation stubs here.
2. Resolution of the pinned library versions (`gradle/libs.versions.toml`) and container image tags (`postgres:16.4-alpine`, `keycloak:26.0.5`, `vault:1.17.6`, `rabbitmq:4.0.5-management-alpine`, `redis:7.4.1-alpine`, `nginx-unprivileged:1.27.3-alpine`, Temurin 21). Any tag or version that turns out not to exist is corrected to the nearest existing patch; this is not a design change.
3. `docker build` of `iam-core` / `iam-web`, `docker compose up`, the Flyway V1 run against PostgreSQL, and the Trivy image scans.
4. The GitHub Actions workflows themselves. The repository has not been pushed from this session: GitHub write access is not attached, and commits are made only on the owner's instruction.

## 5. Phase 1 acceptance criteria (from Phase 0 report §11)

| Criterion | Status |
|---|---|
| Architecture document and logical data model approved | **Awaiting approval** (this gate) |
| Monorepo builds; CI runs build, unit tests, ArchUnit/Modulith verification, Gitleaks, Semgrep, Trivy on every push | **Implemented, pending first CI run** (C1) |
| `docker compose up` starts the dev stack with no hardcoded secrets | **Implemented, config and policy validated; start pending** (C1). No secret exists in any tracked file: dev secrets are generated into a git-ignored folder |
| Provider SPI v1, capability enum, and error model published as versioned contracts | **Met** (tested) |
| Each §83 question answered for every planned deployable | **Met** (ARCHITECTURE §8, 15 components × 10 questions) |

## 6. Gate conditions and open items

**C1 (condition for closing the gate).** Push the Phase 1 changes to a branch and obtain one green run of `ci.yml`: backend, web, contracts, security, and images jobs. Also run `docker compose up` once, with Flyway V1 applied and `iam-core` readiness `UP`. Any failure caused by an unavailable version or tag is fixed within Phase 1 scope.

**Open questions carried forward.** Phase 1 used the documented defaults in their place:

| ID | Default used | Needed by |
|---|---|---|
| Q-01 Existing IdP / Keycloak | New Keycloak; federation possible later | Phase 2 |
| Q-02/Q-03 Existing vault / PAM | New Vault; no migration assumed | Phase 2 / 3 |
| Q-04/Q-05 Air-gapped? registry? | Internet-connected CI; no image push until a registry is chosen | Phase 2 (before deployment) |
| Q-06 CI platform | GitHub Actions (ADR-0011), GitLab-portable | Phase 2 |
| Q-15 Local break-glass admin outside Keycloak | Not implemented (fail closed) | Phase 2 |
| Q-18 Scale targets | 10k identities / 100k accounts / 5k targets / 200 sessions | Phase 2 |
| Q-19 Retention | Audit 7 years, recordings 1 year | Phase 2 |
| Q-20 Compliance frameworks | Framework-neutral evidence model | Phase 4 |
| Q-21 UI languages | English + Arabic (RTL), already in the web shell | Phase 2 |

**Risks updated.** R-13 (unknown estate) is unchanged. A new risk, R-16, is added: the build sandbox cannot resolve dependencies, so compile and runtime verification of Spring and container artifacts depends on CI (mitigated by C1). A further ecosystem risk is recorded: Redis licensing and MinIO distribution changes. The cache is Redis-compatible with Valkey as a drop-in, and the object-store choice is deferred to Phase 6 (ADR-0008 amendment).

## 7. Proposed Phase 2 scope (for authorization)

Phase 2 — IAM Foundation, per the Phase 0 plan and Addendum G:

- Organization, Person, Identity, and Platform User.
- Keycloak OIDC through the BFF, with MFA (TOTP/WebAuthn/passkeys) and step-up.
- RBAC with scopes and server-side authorization on every endpoint, including an endpoint-coverage test.
- Audit subsystem with hash chain, triggers, and a verifier.
- Operation model, transactional outbox, and relay.
- Notifications (SMTP), the health aggregator API, and provider/target registry metadata.
- `iam-scheduler` skeleton for expiry.
- The admin UI shell with the baseline §61 navigation.

The Phase 2 gate will include the failure tests for Redis, RabbitMQ, Vault, and Keycloak outages, plus the IDOR, authorization-matrix, audit-integrity, and secret-leak tests.

---

**STOP — Phase 1 gate.** Phase 2 will not begin until it is explicitly authorized.
