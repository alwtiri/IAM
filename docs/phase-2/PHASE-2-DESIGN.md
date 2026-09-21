# Phase 2 — IAM Foundation: Requirements & Design

| Field | Value |
|---|---|
| Phase | 2 — IAM Foundation |
| Authorized | 2026-09-21 — Phase 1 gate approved by the owner with condition C1 (first local run + green CI) still open |
| Baseline | Master Prompt · Phase 0 (Addendum G) · Phase 1 architecture, domain/data model, ADR-0001…0014 |
| New ADRs | ADR-0015 (persistence with Spring JdbcClient), ADR-0016 (BFF authentication and platform-user bootstrap) |

## 1. Scope

| # | In scope (Phase 2) | Spec |
|---|---|---|
| S1 | Organization model: organization, org units (business unit / department / team, hierarchical), positions, locations | §5 |
| S2 | Person, Identity (8 types, lifecycle), Platform User (Keycloak subject link) | §6 |
| S3 | Authentication: Keycloak OIDC via the Core BFF (browser), bearer JWT (automation), MFA enforced by the Keycloak realm, step-up by `acr`/`auth_time` | §25, G2 |
| S4 | RBAC with permission catalog, built-in roles (§19), scoped role assignments, server-side checks on every endpoint and object, scope-filtered lists, endpoint-coverage test | §19 |
| S5 | Audit subsystem: in-transaction writes, SHA-256 hash chain, UPDATE/DELETE/TRUNCATE blocked in the database, verifier API | §49–§50 |
| S6 | Operation model and transactional outbox with relay routing to RabbitMQ (AMQP destinations) and SMTP (email notifications) | §46–§48 |
| S7 | Notifications (email via SMTP, outbox-driven) | §57 |
| S8 | Secrets foundation: Vault client (AppRole), `SECRETS_UNAVAILABLE` fail-closed mapping, provider-instance credentials stored only in Vault | §26, G3 |
| S9 | Target and provider-instance registry (metadata only; no provider execution) | §10–§11 |
| S10 | System health aggregator with affected-functionality mapping; capability catalog; system info | §62 |
| S11 | Expiry enforcement: expired role assignments and identities are ignored at decision time and swept by a scheduled job | §7, §19 |
| S12 | Admin UI shell with the §61 baseline navigation; Phase 2 pages (Dashboard/health, Users, Roles, Role assignments, Audit logs, Targets, Providers); other entries shown as "planned for Phase N" | §61 |

Out of scope: Joiner/Mover/Leaver workflows, SoD engine, policy engine, risk, access requests, approvals (Phase 4); providers and workers (Phase 3); gateways/sessions (Phase 6); reports (Phase 7); discovery/reconciliation (Phase 8).

## 2. Requirements

### 2.1 Functional
- F1 Create/read/update org units (tree), positions, locations. Moving an org unit is Phase 4 (mover impact).
- F2 Create/read/update persons; manager relation without cycles.
- F3 Create identities for a person; type-specific rules (validity end date required for EMERGENCY/TEMPORARY/CONTRACTOR/EXTERNAL); lifecycle transitions `PENDING→ACTIVE`, `ACTIVE→SUSPENDED`, `SUSPENDED→ACTIVE`, `ACTIVE|SUSPENDED→DISABLED` with reasons.
- F4 Link an identity to a Keycloak subject (platform user); one subject ↔ one identity.
- F5 One-time bootstrap: when no platform user exists and `IAM_BOOTSTRAP_ADMIN_SUBJECT` is configured, the first login of that Keycloak subject creates Person, Identity, Platform User and a GLOBAL Platform Administrator assignment, fully audited. Never runs again once any platform user exists.
- F6 Read the permission catalog and roles; create/revoke role assignments with scope and validity; self-assignment forbidden.
- F7 `GET /api/v1/me`: current identity, effective permissions with scopes, authentication context.
- F8 Audit query with filters (actor, action, object, time range) and chain verification.
- F9 Operation list/detail (scope-filtered).
- F10 Targets: create/read/update metadata. Provider instances: create (credential written to Vault; reference stored), read, enable/disable.
- F11 System info, health, capability catalog.

### 2.2 Security (fail closed)
- SEC1 Every `/api/**` handler declares `@RequiresPermission` or `@PublicEndpoint`; a build test fails otherwise.
- SEC2 Object-level check in application services; out-of-scope objects return `404 NOT_FOUND`.
- SEC3 Unknown Keycloak subject → `403 ACCESS_DENIED` (no auto-provisioning except F5).
- SEC4 Roles/scopes never read from tokens; only `sub`, `acr`, `auth_time`, `amr`.
- SEC5 Step-up: role-assignment changes, provider credentials, audit verification export require `acr` ≥ configured MFA level and `auth_time` ≤ 5 min → otherwise `403 STEP_UP_REQUIRED`.
- SEC6 Any authorization evaluation error → deny.
- SEC7 Audit write failure rolls back the business change (same transaction).
- SEC8 No secret values in DB, logs, responses; Vault unavailable → `503 SECRETS_UNAVAILABLE`, non-secret functions unaffected.
- SEC9 CSRF protection for cookie sessions (`XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header); bearer requests are CSRF-exempt.

### 2.3 Resilience
- RES1 RabbitMQ down → business writes succeed; outbox rows wait; delivered once after recovery.
- RES2 SMTP down → notifications retry with back-off; nothing else affected.
- RES3 Vault down → only secret operations fail (`SECRETS_UNAVAILABLE`).
- RES4 Redis down → no functional effect (Phase 2 Core does not depend on the cache).
- RES5 Keycloak down → new logins fail; existing sessions continue until expiry.
- RES6 Health checks run with individual timeouts in parallel; one hanging dependency does not delay the others.

## 3. Design decisions

### 3.1 Code structure per module (hexagonal, verifiable)
`domain` (pure Java: aggregates, invariants, state machines) · `application` (pure Java use-case services depending on ports) · `infrastructure` (Spring: JDBC repositories, web controllers, configuration). Application services are plain classes wired by Spring `@Configuration`; transactions go through the `TransactionRunner` port (Spring `TransactionTemplate` in production). Consequence: all business rules are unit-testable without Spring.

### 3.2 Avoiding module cycles
Authorization depends on identity, so identity cannot call authorization. Cross-cutting security contracts live in `shared.api.security` (`CurrentActor`, `AccessGuard`, `ResourceScope`, `ScopeFilter`, `Permissions` catalog) and are **implemented** by the authorization and identity modules (dependency inversion). Health checks are contributed by each module through `shared.api.health.ComponentHealthCheck`; the health module depends only on `shared`.

Module dependency updates (MODULE-BOUNDARIES.md): `provider` may use `secrets::api` (credential storage); `health` depends only on `shared`; `notification` may use `audit::api`.

### 3.3 Authorization and scopes
- Permission = `resource:action` string constant in `Permissions`; catalog seeded in `authorization.permission`.
- Scope = set of elements `(type, value)`, types `GLOBAL, ORG_UNIT, ORG_UNIT_TREE, ENVIRONMENT, PROVIDER_TYPE, PROVIDER_INSTANCE, TARGET`. Evaluation: GLOBAL matches everything; otherwise elements are grouped by dimension (org, environment, provider, target); **all constrained dimensions must match (AND), any element within a dimension may match (OR)**; if the resource lacks a constrained attribute, no match (fail closed).
- `AccessGuard.require(permission, resourceScope)` for single objects; `AccessGuard.filter(permission)` returns a `ScopeFilter` (global or set of org-unit paths / environments / provider ids / target ids) applied in SQL for lists.
- Decisions ignore assignments outside `[valid_from, valid_until)` and assignments of identities not `ACTIVE`.

### 3.4 Audit chain
Single chain partition `global` in Phase 2 (sufficient for the initial scale; sharding by partition key is supported by the schema). Write path within the business transaction: lock `audit.chain_head` row (`SELECT … FOR UPDATE`), compute `hash = SHA-256(prev_hash ‖ canonical_json(event))`, insert event with `seq = last_seq + 1`, update head. Canonical JSON: fixed field order, UTC ISO-8601 timestamps, `null` for absent values, sorted detail keys. Triggers reject UPDATE/DELETE/TRUNCATE. Verifier recomputes hashes in sequence order. Signed checkpoints (Vault Transit) are scheduled for Phase 9 hardening; table exists from Phase 2.

### 3.5 Outbox relay routing
Destinations: `amqp:<exchange>/<routingKey>` → RabbitMQ publisher (publisher confirms); `smtp` → mail sender. Relay polls `FOR UPDATE SKIP LOCKED` batches (multi-instance safe), marks published on success, increments attempts with exponential back-off (`next_attempt_at`) on failure, and parks messages after max attempts (`status = PARKED`, alert). Per-destination failures do not block other destinations (grouped processing).

### 3.6 Authentication (ADR-0016)
Spring Security: OAuth2 Login (authorization code + PKCE, confidential client `iam-core`) for the browser with a server-side session; OAuth2 Resource Server (JWT) for bearer clients. `/api/**` accepts either. Actor resolution maps `sub` → platform user → identity on every request (cached per request). Session store: servlet session in Phase 2; Spring Session JDBC is introduced with HA in Phase 10 (sticky sessions are not required for bearer clients).

### 3.7 Expiry enforcement (deviation from Phase 1 plan)
The Phase 1 plan listed an `iam-scheduler` container skeleton for expiry in Phase 2. Expiry is enforced at decision time (authoritative) and swept by an in-Core scheduled job (see correction below). Correction during implementation: instead of an advisory lock, the expiry jobs claim rows with `SELECT … FOR UPDATE SKIP LOCKED`, which is equally safe with several Core replicas and needs no session-level lock. The separate `iam-scheduler` container is introduced in Phase 8 when discovery, reconciliation, and rotation schedules exist; a container with a single job would add operational surface without isolation benefit. Recorded in the Phase 2 gate report.

## 4. API (additive to `iam-core-v1.yaml`)

| Method & path | Permission | Step-up |
|---|---|---|
| `GET /api/v1/me` | authenticated | — |
| `GET/POST /api/v1/org-units`, `GET/PATCH /api/v1/org-units/{id}` | `org:read` / `org:write` | — |
| `GET/POST /api/v1/locations`, `GET/POST /api/v1/positions` | `org:read` / `org:write` | — |
| `GET/POST /api/v1/persons`, `GET/PATCH /api/v1/persons/{id}` | `person:read` / `person:write` | — |
| `GET/POST /api/v1/identities`, `GET /api/v1/identities/{id}` | `identity:read` / `identity:write` | — |
| `POST /api/v1/identities/{id}:activate|suspend|reinstate|disable` | `identity:lifecycle` | yes (disable) |
| `PUT /api/v1/identities/{id}/platform-user` | `identity:platform-user` | yes |
| `GET /api/v1/permissions`, `GET /api/v1/roles`, `GET /api/v1/roles/{id}` | `role:read` | — |
| `GET/POST /api/v1/role-assignments`, `POST /api/v1/role-assignments/{id}:revoke` | `role-assignment:read` / `role-assignment:write` | yes (write) |
| `GET /api/v1/audit-events`, `GET /api/v1/audit/verification` | `audit:read` / `audit:verify` | verification: yes |
| `GET /api/v1/operations`, `GET /api/v1/operations/{id}` | `operation:read` | — |
| `GET/POST /api/v1/targets`, `GET/PATCH /api/v1/targets/{id}` | `target:read` / `target:write` | — |
| `GET/POST /api/v1/provider-instances`, `GET /api/v1/provider-instances/{id}`, `POST …/{id}:enable|disable` | `provider:read` / `provider:write` | yes (create) |
| `GET /api/v1/system/info`, `/system/health`, `/capabilities/catalog` | `system:read` / `system:health:read` | — |

## 5. Data (migrations V2–V8, forward-only)

V2 audit (event, chain_head, checkpoint, evidence_link, immutability triggers) · V3 operation (operation with verified-success constraint, outbox_message, processed_message) · V4 organization (+ default organization) · V5 identity (person, identity, platform_user, platform_setting, SYSTEM identity) · V6 authorization (permission, role, role_permission, role_assignment, role_assignment_scope) + seed of 20 permissions and 11 built-in roles · V7 target & provider registry · V8 notification. Flyway's history table lives in schema `platform` (public is closed for object creation).

## 6. Phase 2 acceptance criteria

The Phase 0 §11 Phase 2 criteria apply unchanged; additionally: every endpoint in the table above exists in OpenAPI and code; endpoint-coverage test passes; bootstrap admin works exactly once; RES1–RES6 covered by tests (unit-level here, Testcontainers-level in CI).
