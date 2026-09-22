# Module Boundaries

| Field | Value |
|---|---|
| Phase | 1 |
| Decision | ADR-0002 |
| Enforcement | `core/src/test/java/.../architecture/ModuleBoundaryTest.java` (ArchUnit) and `ModularityTest.java` (Spring Modulith verify), run on every build |

## 1. Gradle projects

| Project | Kind | May depend on | Must never depend on |
|---|---|---|---|
| `shared-kernel` | plain Java 21 library | JDK only | anything else |
| `provider-spi` | plain Java 21 library | `shared-kernel` | Spring, any provider, core |
| `provider-spi-testkit` | test library (JUnit 5) | `provider-spi` | core |
| `core` | Spring Boot application `iam-core` | `shared-kernel`, `provider-spi` (types only: capability model, result types) | `providers/*`, `gateways/*`, `worker`, `agent`, `integrations/*` |
| `worker` (Phase 3) | Spring Boot application | `shared-kernel`, `provider-spi`, `providers/*` (runtime classpath) | `core` internals |
| `providers/<type>` (Phase 3+) | plain Java libraries | `provider-spi` | `core`, other providers |
| `gateways/<channel>` (Phase 6) | applications | `shared-kernel`, generated internal API client | `core` internals |

## 2. `core` application modules

Base package `com.enterprise.iam.core`. Each direct sub-package is a module. Only the module's root package and its `api` sub-package are public; `application`, `domain`, `infrastructure` are internal.

| Module | Allowed dependencies (their `api` only) |
|---|---|
| `shared` (in-app cross-cutting: security context, correlation, error mapping) | — |
| `audit` | shared |
| `organization` | shared, audit |
| `identity` | shared, audit, organization |
| `authorization` | shared, audit, identity, organization |
| `policy` | shared |
| `risk` | shared |
| `sod` | shared, audit, authorization |
| `target` | shared, audit, organization |
| `provider` | shared, audit, target, secrets (credential storage — Phase 2) |
| `operation` | shared, audit |
| `secrets` | shared, audit, operation |
| `account` | shared, audit, identity, target, provider, operation, secrets |
| `request` | shared, audit, identity, account, target, policy, risk, sod, authorization, operation |
| `approval` | shared, audit, request, sod, identity |
| `session` | shared, audit, request, account, secrets, policy, target |
| `notification` | shared, operation, audit, identity, authorization (event listeners — Phase 2) |
| `reporting` | read-only query APIs of any module |
| `search` | read-only query APIs of any module |
| `health` | shared (checks are contributed by modules via `shared.api.health.ComponentHealthCheck` — Phase 2) |

Modules reference the shared kernel as `shared::*`, meaning any explicitly exposed named interface of `shared`. The reason is that `shared.api` is split into sub-packages (`security`, `paging`, `tx`, `jdbc`, `health`, `events`, `context`), each annotated `@NamedInterface("api")`. Spring Modulith 2.0 does not merge same-named interfaces declared on several packages, so `shared::api` resolved to only one of them. `shared.infrastructure` stays inaccessible because it is not a named interface. Cycles are forbidden. Cross-cutting security contracts (`CurrentActor`, `AccessGuard`, `ResourceScope`, `ScopeFilter`, `Permissions`, `BootstrapAdministratorGrant`) live in `shared.api.security` and are implemented by the authorization and identity modules (dependency inversion, PHASE-2-DESIGN §3.2). Reactions flowing "upward" (e.g. `approval` completing a `request`) use domain events, not direct calls.

## 3. Rules enforced by tests

1. No class in `core` references packages `..providers..`, `..gateway..`, `..agent..`, `..worker..`, `..integration..`.
2. `shared-kernel` and `provider-spi` contain no `org.springframework` references.
3. `domain` packages do not depend on Spring, JPA, or web types.
4. Controllers live only in `..api.web..` or `..infrastructure.web..` and do not access repositories directly.
5. No field or DTO of type `char[]`/`String` named like `password|secret|token|privateKey` outside `shared-kernel`'s `Secret` type (Phase 2 rule).
6. Module dependency graph matches the table above (Spring Modulith `allowedDependencies`).
