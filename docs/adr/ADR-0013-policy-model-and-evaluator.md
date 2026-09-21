# ADR-0013: Policy model and in-process deterministic evaluator

- **Status:** Accepted (Phase 1)
- **Date:** 2026-09-21

## Context

Spec §23 requires a central policy engine returning ALLOW/DENY/REQUIRE_* decisions, not business rules scattered in services; §48 requires deterministic synchronous decisions; §4 fail-closed.

## Decision

- Policies are versioned, immutable-once-published documents stored in PostgreSQL (`policy` schema). Structure: `target` (which requests/actions it applies to), `conditions` (typed predicates over a fixed context: identity, role, account, target, privilege, environment, time, location, risk, emergency, approval state, SoD result, session type, access channel), `effect` (ALLOW / DENY), and `obligations` (REQUIRE_APPROVAL, REQUIRE_MFA, REQUIRE_STEP_UP_AUTH, REQUIRE_JUSTIFICATION, REQUIRE_DUAL_CONTROL, REQUIRE_RECORDING, REQUIRE_SESSION_TIMEOUT with parameters).
- Combining algorithm: **deny-overrides**; obligations from all matching ALLOW policies are unioned; no matching policy → DENY.
- Evaluator runs in-process in `iam-core`, is a pure function of (policy set version, context), and records the policy set version, matched policies, and inputs hash with every decision (explainability and audit).
- Any evaluation error → DENY (fail closed).
- External engines (OPA/Rego, Cedar) were considered: rejected for Phase 1 because they add a network hop or a second language to the decision path; the model is kept close enough to Cedar/XACML semantics that an adapter remains possible.

## Consequences

+ Deterministic, testable (property tests), explainable.
- Policy authoring UI must be built (Phase 4/7).
