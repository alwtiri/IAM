# Phase 2 Gate Report — IAM Foundation

| Field | Value |
|---|---|
| Phase | 2 — IAM Foundation |
| Date | 2026-09-22 |
| Authorization | Phase 1 gate approved by the owner on 2026-09-21 with condition C1 open |
| Design | [PHASE-2-DESIGN.md](PHASE-2-DESIGN.md) · ADR-0015 (JdbcClient persistence) · ADR-0016 (BFF authentication and bootstrap) |
| Status | **Submitted for gate approval. C2 runtime checks complete (27/27 on the owner's server, §6.1); the backend build on PR #6 (test-compile fix) is the last CI item; Security scans are now green. Phase 3 not started** |
| Decision requested | Approve Phase 2 subject to condition **C2** (§6) and authorize Phase 3 — Core Providers |

---

## 1. Phase 1 condition C1 — status

| Part | Result |
|---|---|
| Local run on the owner's server (`registry`, 192.168.136.139) | **Met.** All 10 containers are healthy. Flyway applied V1 into schema `platform`, and `iam-core` (Spring Boot 4) started in 1.5 s. |
| Defects found by the first run, all fixed | (1) Gradle had no Maven repositories configured. (2) The dev-secret generator stopped after the first file (SIGPIPE under `pipefail`). (3) Flyway tried to create its history table in `public`, which is closed for object creation. |
| Green CI run on GitHub | **Open** (see §6.1). The repository is on GitHub (PR #1, #2, #5 merged into `main`). On PR #6, Web, Contracts, and Security scans are green; the backend test-compile defect is fixed and awaiting CI. |

## 2. What Phase 2 delivers

| Scope item (design §1) | Implementation | Evidence |
|---|---|---|
| S1 Organization model | `organization` module. Tree of business units, departments, and teams, with a materialized path and a kind hierarchy. Position and location catalogs. Migration V4. | Covered by the authorization and identity suites; `PostgresMigrationIT` (CI) |
| S2 Person / Identity / Platform User | `identity` module. Person validation with manager-cycle detection, 8 identity types with validity rules, lifecycle state machine, platform-user link. Migration V5, which also seeds the SYSTEM identity (DB trigger: it can never have a login). | `IdentityServicesTest` (7) |
| S3 Authentication | Browser: OIDC authorization code + PKCE through the BFF, tokens kept server-side. Automation: JWT resource server (issuer and audience validated). Neither path calls Keycloak at startup. Step-up is accepted on `acr` or `amr` and forced via `prompt=login`. The dev realm enforces TOTP, brute-force protection, and a password policy. | `ScopeTest.stepUpRequiresRecentMfa`; runtime login → C2 |
| S4 RBAC with scopes | `authorization` module. 20 permissions and 11 built-in roles (§19); built-in roles are immutable by trigger. Scoped, time-bound assignments. The fail-closed `AccessGuard` returns 404 for out-of-scope objects and filters lists in SQL. Escalation protection: no self-assignment (also a DB CHECK), the grantor must hold every permission of the role within the scope, GLOBAL is grantable only by GLOBAL holders, and the last GLOBAL platform administrator is protected. Migration V6. | `RoleAssignmentServiceTest` (10), `PermissionCatalogTest` (2), `EndpointSecurityCoverageTest` (CI) |
| S5 Audit subsystem | Audit is written in the same transaction as the change and chained with SHA-256 over a canonical form. Timestamps are truncated to DB precision. Triggers reject UPDATE, DELETE, and TRUNCATE. A verifier API detects modification, deletion, and tail truncation. Reading audit data is itself audited. Migration V2. | `HashChainTest` (6), `AuditServiceTest` (4), `PostgresMigrationIT` (CI) |
| S6 Operation model and outbox | Operation state machine; a DB CHECK prevents a mutating operation from being SUCCESS without verification. The transactional outbox uses claim-then-dispatch (no network I/O under row locks) with `SKIP LOCKED`, per-kind circuit breaking, exponential back-off, and parking. AMQP publisher confirms. Migration V3. | `OutboxRelayTest` (6), `OperationTest` (4), `PostgresMigrationIT` (CI) |
| S7 Notifications | E-mail through the outbox, with plain-text templates and header-injection protection; duplicates are skipped on redelivery. Identity lifecycle events are published to `iam.events`. Without `SPRING_MAIL_HOST` nothing is queued and health reports SMTP as "not configured" — no fake delivery. Mailpit serves as the dev SMTP sink. Migration V8. | `NotificationTest` (3) |
| S8 Secrets foundation | Framework-free Vault client: AppRole login with an in-memory token and re-login, KV v2, strict timeouts, path validation, fail closed. Provider credentials are written to Vault before metadata and the Vault entry is removed if the DB write fails. `vault-init-dev.sh` configures KV, AppRole, and a least-privilege policy. | `VaultClientTest` (4), `ProviderRegistryServiceTest` (4) |
| S9 Target and provider registry | Target metadata (§10 attributes, Windows Server ≠ AD). Provider instances with SPI-validated settings (secret-like keys rejected) and a Vault reference only. Capability catalog from the SPI. Migration V7. | `ProviderRegistryServiceTest` |
| S10 Health, info, catalog | Parallel health aggregation with per-check timeouts and a 5 s cache. Each component carries a classification and the functionality it affects. Probes: PostgreSQL, Keycloak, Vault, RabbitMQ (plus outbox backlog), cache (RESP AUTH/PING), SMTP. | `HealthAggregatorTest` (2) |
| S11 Expiry enforcement | Expired role assignments and identities are ignored at decision time, and swept by jobs that are safe across replicas (`SKIP LOCKED`). | `RoleAssignmentServiceTest.expiredAssignments…`, `IdentityServicesTest.typeRulesAndExpiry` |
| S12 Admin UI | The complete §61 navigation (10 sections, 51 pages) in English and Arabic with RTL. Phase 2 pages: health dashboard, users, roles, audit log, targets, providers. Entries of later phases show an explicit "planned for Phase N" page with no simulated data. Includes the step-up flow and CSRF handling. | Web tests (7) |
| API contract | OpenAPI v1 now has 32 paths covering every Phase 2 endpoint, with `x-iam-permission` and `x-iam-step-up` annotations. Lint shows 0 warnings. The enum-sync check covers 6 enums. | `redocly lint`, `ci/check-contract-sync.py` |

## 3. Gate clarifications in Phase 2

- **G2 (Keycloak):** roles and scopes are never read from tokens. Unknown subjects are denied and audited. Keycloak down → existing sessions and cached JWKS continue, new logins fail.
- **G3 (Vault):** a Vault outage affects only secret operations (`SECRETS_UNAVAILABLE`); there is no plaintext cache, and a Semgrep rule forbids secret caches.
- **G4/G5 (workers and providers):** the outbox relay isolates failing destination kinds from each other. The DB rejects a verified-success violation.
- **G8 (Account Management):** the account schema is reserved for Phase 3.
- **G1 (Docker-first):** 10 hardened services pass the compose policy, and the public URL and bind address are configurable for LAN access.

## 4. Verification evidence

### 4.1 Verified in this environment

| Check | Result |
|---|---|
| Framework-free code (kernel, SPI, every core `api`/`domain`/`application` package): `javac --release 21 -Xlint:all -Werror` | Pass |
| Unit, contract, and application tests (Phase 1 and Phase 2) | **83 / 83 pass.** Run with a local JUnit-API runner because JUnit cannot be downloaded here; the same sources run under JUnit 5 in Gradle. |
| Web: strict `tsc`, 7 vitest tests, production build | Pass |
| Java syntax check of all 260 Java files (tree-sitter), including the Spring adapters | 0 problems |
| All 8 Flyway migrations and their PL/pgSQL bodies parsed with the PostgreSQL parser (pglast) | Pass |
| OpenAPI lint; Java ↔ contract enum sync (ErrorCode, CapabilityStatus, IdentityState, IdentityType, OperationStatus, ProviderOperation) | Pass |
| `docker compose config`; compose hardening policy (10 services) | Pass |
| Project Semgrep rules | 0 findings |
| Module graph declared in `package-info` (acyclic, matches MODULE-BOUNDARIES.md) | Pass |

### 4.2 Not verifiable here — covered by condition C2

The sandbox cannot download Maven artifacts or run Docker. The following were written carefully against the Spring Boot 4 / Spring Security 7 / Spring AMQP APIs but **have not been compiled or executed yet**:

1. **Spring adapters:** JDBC repositories, controllers, security configuration, AMQP and mail dispatchers, and module configurations.
2. **Tests that need a Spring or Docker runtime:** `ModularityTest`, `ModuleBoundaryTest`, `EndpointSecurityCoverageTest`, and `PostgresMigrationIT`. The last one assumes the Testcontainers 2.x artifact and package names managed by Boot 4; it is adjusted if the first build disagrees.
3. **Runtime behaviour against real services:** Keycloak realm import with `${…}` placeholders, the OIDC login and bootstrap, the `amr` mapper, Vault AppRole, RabbitMQ publisher confirms, and Mailpit delivery.

The Phase 1 run on the owner's server already confirmed a good share of the Phase 1 artefacts on first contact with real infrastructure: the Boot 4 dependency set, the Docker build, the compose topology, and the secrets and roles bootstrap. The three defects it found were fixed within hours.

## 5. Phase 2 acceptance criteria (Phase 0 report §11)

| Criterion | Status |
|---|---|
| Person and Identity are separate aggregates; all eight identity types supported | **Met** (tested) |
| Login via Keycloak OIDC with TOTP and WebAuthn; step-up enforced for sensitive endpoints | **Implemented.** TOTP is enforced by the realm. WebAuthn/passkeys are enabled as required actions but need HTTPS or `localhost` in browsers. Runtime verification → C2. |
| Every endpoint has a server-side authorization check; automated test enumerates endpoints | **Implemented.** The interceptor denies undeclared handlers; `EndpointSecurityCoverageTest` enumerates all `@RestController` handlers → C2. |
| IDOR: Department A scope cannot read or modify Department B via API or search | **Met at service level** (`RoleAssignmentServiceTest`, `ScopeTest`: identical in-memory and SQL semantics); API-level → C2 |
| Audit in the same transaction; UPDATE/DELETE rejected by DB; verifier detects modification | **Met** at domain level (tested); DB level in `PostgresMigrationIT` → C2 |
| Failure: Redis down → login and API work | **Met by design**: the Core has no runtime dependency on the cache in Phase 2; health reports it as OPTIONAL |
| Failure: RabbitMQ down → messages queued and delivered after recovery without loss | **Met** (`OutboxRelayTest`) |
| Failure: Vault down → non-secret functions work; secret endpoints return `SECRETS_UNAVAILABLE` | **Met** (`VaultClientTest`, `ProviderRegistryServiceTest`) |
| No secret, token, or password in logs | **Implemented** (`Secret` type, error model, request DTO `toString` redaction, Semgrep rules); log-scan test at runtime → C2 |
| Bootstrap admin works exactly once | **Met** (`IdentityServicesTest`, DB marker row) |

## 6. Gate conditions

**C2 (replaces C1's open part).** On the owner's server and in CI:

1. **Build and tests.** `./gradlew build` is green: compile, all unit tests, ArchUnit/Modulith, endpoint coverage, and `PostgresMigrationIT`.
2. **Stack and migrations.** `docker compose up -d --build` followed by `./scripts/vault-init-dev.sh` gives all services healthy, and migrations V2–V8 are applied.
3. **Smoke test** (checklist in [DEVELOPMENT.md](../DEVELOPMENT.md)):
   - First login as `iam-admin`, including TOTP enrolment and the bootstrap.
   - `/api/v1/me` shows PLATFORM_ADMINISTRATOR.
   - Create an org unit, a person, and an identity, then activate the identity.
   - Grant a scoped role; this must ask for step-up.
   - The audit log shows the events and the chain verification is valid.
   - Registering a provider instance stores its credential in Vault; stopping Vault makes that call return 503 `SECRETS_UNAVAILABLE` while identities stay readable.
   - The e-mail for the role grant is visible in Mailpit.
4. **Fixes.** Any compile or runtime defect found is fixed within Phase 2 before Phase 3 starts.

### 6.1 C2 status (2026-09-22)

| C2 item | Result | Evidence |
|---|---|---|
| 1. Build and tests | **Open: fix pending CI.** PR #6 showed that `PostgresMigrationIT` did not compile, because the PostgreSQL driver is runtime-only while the test imported `PGSimpleDataSource`. The test now uses Spring's `DriverManagerDataSource`. An earlier reading of PR #1 as green was wrong: the backend job cannot have compiled this test. `ci/local-build.sh` now reproduces the CI build locally. | PR #6 job log |
| 2. Stack and migrations | **Met.** All services are healthy and V1–V8 are applied. The System Health page shows 6/6 components HEALTHY. | Owner's server `registry` |
| 3. Smoke test | **Met: 23/23 PASS** with `deploy/compose/scripts/smoke-phase2.sh` (run `SMOKE-1790030712`). Covers login and bootstrap; org unit, person, and identity with activation; scoped HELPDESK grant with step-up, duplicate rejected (409), and revoke; 404 for unknown objects, 400 validation, 403 without CSRF, 401 without session, actuator hidden; provider credential stored only in Vault (not in the response, GET, or logs) and secret-looking setting keys rejected; audit list, chain valid, DB UPDATE blocked; lifecycle e-mail in Mailpit. | Server console output |
| 3a. Vault outage (503 `SECRETS_UNAVAILABLE`, identities still readable) | **Met: 4/4 PASS** with `smoke-phase2.sh --vault-down` (run `SMOKE-1790039381`, 27/27 overall). With Vault stopped, the non-secret API answers 200, provider registration returns 503 `SECRETS_UNAVAILABLE`, and health reports vault UNAVAILABLE. Vault then restarted and unsealed cleanly. | Server console output |
| 4. Defects fixed within Phase 2 | **Met.** See the list below. | Commits on `main` and `phase-2-closeout` |

Defects found and fixed during C2:

1. **Web and Core error handling.** Non-JSON error pages broke the UI ("Unexpected token '<'"). The web client now reports HTTP errors, and Core permits `/error` and disables the whitelabel page.
2. **Step-up never satisfied.** Keycloak's `amr` mapper emits nothing unless the authenticator executions carry an Authentication Reference. `scripts/keycloak-stepup-dev.sh` now copies the browser flow and tags password as `pwd` and OTP as `otp`, with WebAuthn tagged `hwk`. The script also had a bug of its own: `docker exec` consumed the loop's stdin, so only the first execution was tagged.
3. **CI Security scans.** The `trivy-action@0.28.0` tag no longer resolves, so the action is now pinned to v0.36.0 by commit SHA. Semgrep then reported unpinned base images, and all four are now pinned by digest (ADR-0017). It also flagged plain sockets in the internal health probes; these are annotated, with TLS tracked for Phase 10.
4. **Process.** Owner directive: local-first Git workflow ([GIT-WORKFLOW-POLICY](../process/GIT-WORKFLOW-POLICY.md), ADR-0017). `ci/local-checks.sh` runs the checks locally, and a git bundle that was committed to `main` by upload is removed.

**Deviations recorded:**

- The `iam-scheduler` container moves to Phase 8 (design §3.7). Expiry is enforced at decision time and swept inside the Core using `SKIP LOCKED`.
- JPA is replaced by JdbcClient (ADR-0015).
- Spring Session JDBC is deferred to Phase 10.
- Signed audit checkpoints move to Phase 9.
- WebAuthn over plain-HTTP LAN addresses is not possible (browser rule); TOTP is used in development.

**Open questions:** unchanged from Phase 1 (Q-01…Q-06, Q-15, Q-18…Q-21), answered with the documented defaults. Q-07/Q-08/Q-09 (AD, Linux, Windows estate details) are **needed before Phase 3 provider work starts**.

## 7. Proposed Phase 3 scope (for authorization)

Phase 3 — Core Providers:

- **Worker runtime:** the `iam-worker` image with provider pools (ADR-0010), the credential-handle redemption API (mTLS), and the result consumer.
- **Account Management Core:** accounts, managed accounts, entitlements, governance states, findings (orphan, unmanaged, dormant, unexpected, privileged-without-owner).
- **Providers:**
  - Linux (SSH)
  - Windows Local (WinRM)
  - Active Directory (LDAPS)
  - Generic Application (REST/SCIM)

  Each provider must pass the contract kit and Testcontainers-based behavioural tests.
- **Resilience per provider:** circuit breakers, bulkheads, and timeouts, plus the failure tests "VMware-style outage ≠ Linux blocked".

---

**STOP — Phase 2 gate.** Phase 3 will not begin until it is explicitly authorized.
