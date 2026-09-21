# ADR-0016: BFF authentication, actor resolution, and one-time platform bootstrap

- **Status:** Accepted (Phase 2)
- **Date:** 2026-09-21

## Context

ADR-0004 and gate clarification G2 fix Keycloak as authentication-only. The browser must not hold tokens (threat T01). The platform database is authoritative for identities and roles, so a login by an unknown Keycloak user must not create access. The very first administrator must nevertheless be created somehow.

## Decision

1. **Browser:** Spring Security OAuth2 Login (authorization code + PKCE) against the Keycloak realm `iam`, confidential client `iam-core`; tokens are kept server-side in the session; the browser receives only the session cookie and a CSRF cookie (`XSRF-TOKEN`, sent back as `X-XSRF-TOKEN`).
2. **Automation:** OAuth2 Resource Server validating JWTs from the same realm (issuer + audience `iam-core`).
3. **Actor resolution:** for every request, `sub` → `identity.platform_user` → identity; the identity must be `ACTIVE`. Unknown or inactive → `403 ACCESS_DENIED`. Only `sub`, `acr`, `amr`, `auth_time` are read from tokens.
4. **Step-up:** endpoints annotated `@RequiresStepUp` require `acr` in the configured MFA set (default `mfa`, `2`) and `auth_time` within 300 s; otherwise `403 STEP_UP_REQUIRED` and the UI re-authenticates with `prompt=login&acr_values=mfa`.
5. **Bootstrap:** if and only if `identity.platform_user` is empty and `IAM_BOOTSTRAP_ADMIN_SUBJECT` equals the authenticated `sub`, the Core creates Person + Identity (EMPLOYEE) + Platform User + a GLOBAL `PLATFORM_ADMINISTRATOR` assignment in one transaction, audited as `platform.bootstrap`. A database unique marker row (`platform_setting` key `bootstrap.completed`) makes it impossible to run twice, even concurrently.
6. MFA policy is configured in Keycloak (realm required action OTP / WebAuthn); the platform enforces the result through `acr` checks for sensitive operations and requires MFA-level `acr` for all administrative role holders from Phase 9 onward (configurable earlier).

## Consequences

+ No tokens in the browser; roles cannot be injected via Keycloak.
+ Safe, auditable first-admin creation without default passwords.
- Session state lives in the Core instance until Spring Session JDBC is added (Phase 10 HA).
