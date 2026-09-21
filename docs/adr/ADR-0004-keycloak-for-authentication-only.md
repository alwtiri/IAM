# ADR-0004: Keycloak for authentication only; governance stays in the platform

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Spec §25, §60 name Keycloak with OIDC, TOTP, WebAuthn, passkeys. Spec §19–§24 require RBAC, scopes, SoD, policy, and risk to be central, explainable, and enforced server-side. §83 Q10 asks whether components are replaceable.

## Decision

Keycloak authenticates platform users (OIDC Authorization Code + PKCE through a backend-for-frontend), enforces MFA and step-up (acr levels), and can federate an enterprise IdP/AD. Keycloak is **not** the source of truth for platform roles, scopes, entitlements, or SoD; those live in the platform database and are evaluated by the Core on every request. Token claims are used for subject identification and authentication context only.

Keycloak unavailability: no new logins or step-ups (fail closed); access tokens (≤5 min) remain valid until expiry. A sealed local break-glass administrator is an open decision (Phase 0 Q-15).

## Consequences

+ Authorization logic is testable, auditable, and not duplicated across two systems.
+ Keycloak replaceable by another OIDC provider.
- Role changes take effect on the next request, not via token refresh — this is the desired behaviour.

## Amendment — Phase 0 gate (2026-09-21)

Normative (gate clarification G2): Keycloak must NOT be the source of truth for identity governance, Person records, Account Management, RBAC governance, scopes, access requests, approvals, SoD, policies, risk, entitlements, audit, managed accounts, or PAM authorization decisions. The platform database is authoritative. Keycloak unavailable → existing short-lived sessions continue per policy; new authentication and step-up fail closed. Keycloak runs as its own container with its own PostgreSQL database (separate from the platform database) so its lifecycle and backup are independent.

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
