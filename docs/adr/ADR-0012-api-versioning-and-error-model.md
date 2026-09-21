# ADR-0012: API versioning, error model, and contract-first OpenAPI

- **Status:** Accepted (Phase 1)
- **Date:** 2026-09-21

## Context

Spec §72–§73 require a RESTful, documented, versioned, authorized, auditable API with a standardized error model and correlation IDs.

## Decision

- URI major versioning: `/api/v1/...`. Additive changes only within a major version; breaking change → `/api/v2` with overlap period.
- Contract-first: `contracts/openapi/iam-core-v1.yaml` is the source of truth, linted in CI; server stubs and TS client are generated from it (Phase 2).
- Error model per §73, extended: `code`, `message`, `operationId`, `providerId`, `retryable`, `timestamp`, plus `correlationId`, `details[]` (field errors). Media type `application/json`; no stack traces or internal class names.
- Pagination: cursor-based (`limit`, `cursor`) for large collections; `sort`, filter parameters documented per endpoint.
- Idempotency: `Idempotency-Key` header required on POST that creates resources or triggers operations; replay returns the original result.
- Correlation: `X-Correlation-Id` accepted/generated and returned; propagated to logs, audit, operations, messages, and traces.
- Long-running actions return `202 Accepted` with an Operation resource; clients poll or subscribe; never `200 SUCCESS` before verification.

## Consequences

+ Consistent client experience; contracts testable in CI.
- Contract-first requires generator tooling in Phase 2.
