# API Guidelines

| Field | Value |
|---|---|
| Phase | 1 |
| Decision | ADR-0012 |
| Contract | [`contracts/openapi/iam-core-v1.yaml`](../../contracts/openapi/iam-core-v1.yaml) (source of truth, linted in CI) |

## 1. Surfaces

| Surface | Base path | Consumers | Auth |
|---|---|---|---|
| Public API | `/api/v1` | Web UI (via BFF session), automation clients | BFF cookie + CSRF, or OAuth2 bearer |
| Internal extension API | `/internal/v1` | Workers, gateways, agents, scheduler | mTLS client certificate bound to a registered component identity + short-lived service token |
| Health | `/actuator/health/{liveness,readiness}` | Orchestrator | Network-restricted |

Internal endpoints are never routed through the public proxy.

## 2. Resource design

- Plural nouns, kebab-case: `/api/v1/access-requests/{id}`.
- Actions that are not CRUD are sub-resources with verbs as custom methods: `POST /api/v1/access-requests/{id}:submit`, `:approve`, `:revoke`.
- IDs are UUIDs; never sequential numbers.
- Partial update: `PATCH` with JSON Merge Patch; requires `If-Match` with the entity `version` (ETag) — mismatch returns `409 CONCURRENT_MODIFICATION`.

## 3. Versioning

URI major version. Within a major version only additive changes: new endpoints, new optional fields, new enum values (clients must tolerate unknown enum values). Breaking changes → new major version, previous major supported for an announced overlap period.

## 4. Pagination, filtering, sorting

Cursor pagination: `?limit=50&cursor=<opaque>`; response `{ "items": [...], "page": { "nextCursor": "...", "limit": 50 } }`. `limit` max 200. Filtering by named query parameters documented per endpoint (no generic query language exposed). Sorting `?sort=name,-createdAt` over an allow-list of fields. Results are always scope-filtered at query level.

## 5. Idempotency

`Idempotency-Key` (UUID) required for POSTs that create resources or start operations. The server stores key + request hash + response for 24 h; a replay with the same body returns the stored response, a replay with a different body returns `422 IDEMPOTENCY_KEY_REUSED`.

## 6. Long-running operations

Return `202 Accepted` with `Location: /api/v1/operations/{id}` and an Operation body. Operation status values: `QUEUED, RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED, PARTIAL, UNKNOWN`. `SUCCESS` only after verification.

## 7. Error model (§73)

```json
{
  "code": "PROVIDER_UNAVAILABLE",
  "message": "The target provider is currently unavailable.",
  "operationId": "0192c3a4-…",
  "providerId": "0192c3a4-…",
  "retryable": true,
  "timestamp": "2026-09-21T10:15:30Z",
  "correlationId": "5f0c…",
  "details": [ { "field": "duration", "code": "OUT_OF_RANGE", "message": "Maximum is 8h." } ]
}
```

Media type `application/json`. Messages are safe for end users; never contain stack traces, SQL, class names, hostnames of internal components, or secret material. The authoritative code list is `ErrorCode` in `shared-kernel` and the `ErrorCode` schema in the OpenAPI contract; they are kept in sync by a build check.

| HTTP | Typical codes |
|---|---|
| 400 | `VALIDATION_FAILED` |
| 401 | `AUTHENTICATION_REQUIRED` |
| 403 | `ACCESS_DENIED`, `STEP_UP_REQUIRED`, `POLICY_DENIED`, `SOD_CONFLICT` |
| 404 | `NOT_FOUND` (also used when the caller lacks visibility, to avoid object enumeration) |
| 409 | `CONCURRENT_MODIFICATION`, `INVALID_STATE_TRANSITION`, `ALREADY_EXISTS` |
| 422 | `IDEMPOTENCY_KEY_REUSED`, `UNSUPPORTED_CAPABILITY` |
| 429 | `RATE_LIMITED` |
| 503 | `PROVIDER_UNAVAILABLE`, `SECRETS_UNAVAILABLE`, `DEPENDENCY_UNAVAILABLE`, `AUTHENTICATION_UNAVAILABLE` |
| 504 | `OPERATION_TIMEOUT` |
| 500 | `INTERNAL_ERROR` (generic message, correlation ID only) |

## 8. Headers

| Header | Direction | Purpose |
|---|---|---|
| `X-Correlation-Id` | in/out | Correlation across UI → API → policy → provider → worker → target |
| `traceparent` | in/out | W3C trace context |
| `Idempotency-Key` | in | Idempotent creation |
| `If-Match` / `ETag` | in/out | Optimistic concurrency |
| `X-CSRF-Token` | in | BFF cookie sessions |

## 9. Security rules for every endpoint

1. Declares a permission and scope resolver (build fails otherwise — Phase 2 test).
2. Validates input with Bean Validation and explicit size limits.
3. Emits an audit event for every state change and for every sensitive read (secret metadata, recordings, audit export).
4. Returns `404` instead of `403` for objects outside the caller's scope.
5. Rate-limited per identity and per IP; stricter limits on authentication-adjacent and secret endpoints.
