# Threat Model — IAM Core (STRIDE)

| Field | Value |
|---|---|
| Phase | 1 |
| Scope | `iam-core`, `iam-web`, `iam-proxy`, PostgreSQL, Keycloak, Vault, RabbitMQ, cache, internal API toward workers/gateways |
| Out of scope (own threat models before their phase) | PAM gateways (Phase 6), agent (Phase 6+), individual providers (Phase 3/5), integrations (Phase 8) |
| Method | STRIDE per element and data flow; risk = likelihood × impact (H/M/L) |

## 1. Assets

A1 Secret values (Vault) · A2 Governance data (identities, roles, scopes, policies) · A3 Access decisions and grants · A4 Audit trail and evidence · A5 Session recordings (later) · A6 Platform administrator accounts · A7 Component identities (mTLS keys) · A8 Availability of the Core.

## 2. Data flows

DF1 Browser → proxy → Core (BFF) · DF2 Core ↔ Keycloak (OIDC) · DF3 Core ↔ PostgreSQL · DF4 Core ↔ Vault · DF5 Core → outbox → RabbitMQ → workers · DF6 Workers/gateways → Core internal API (mTLS) · DF7 Core → SIEM/SMTP (via outbox).

## 3. Threats and mitigations

| ID | STRIDE | Element | Threat | L | I | Mitigations | Verified by |
|---|---|---|---|---|---|---|---|
| T01 | S | DF1 | Session hijacking / token theft in browser | M | H | BFF: no tokens in browser; HttpOnly/Secure/SameSite=Strict cookie; short idle timeout; bind session to user agent + IP change detection with step-up | Phase 2 session security tests |
| T02 | S | DF6 | Rogue process impersonates a worker/gateway to obtain credentials | M | H | mTLS with per-instance certs from Vault PKI; component registry; handle bound to component + operation; short TTL; single use | Phase 2/3 tests with invalid/expired/revoked certs |
| T03 | T | DF3 | Direct tampering with audit rows by DB user | L | H | App role INSERT/SELECT only; triggers; hash chain; signed checkpoints; WORM archive; verifier alerts | Audit integrity test suite (Phase 2) |
| T04 | T | A2 | Unauthorized role/policy change via API | M | H | RBAC + scope + step-up + SoD (no self-approval) + dual control for GLOBAL; audit | Authorization matrix tests |
| T05 | T | DF5 | Message tampering / injection into queues | L | H | RabbitMQ TLS; per-group credentials and vhost permissions; commands carry operation ID resolved against Core state (workers act only on operations the Core confirms via credential-handle redemption) | Phase 3 tests |
| T06 | R | A3 | User denies having approved/revealed | M | M | Identity-bound decisions with `acr`/`amr`, timestamp, IP, correlation; immutable approval rows; audit | Phase 4 tests |
| T07 | I | A1 | Secret leakage through logs/errors/metrics | M | H | `Secret` type; log redaction; error model; Semgrep rules; secret-leak tests | Phase 2 tests + CI |
| T08 | I | A2 | IDOR / enumeration across departments | H | H | Object-level checks; scope-filtered queries; 404 for out-of-scope; UUIDs | IDOR test suite |
| T09 | I | Search/reports | Data exposure via search or report exports | M | H | Query-level scope filters; export permissions; export audited | Phase 7 tests |
| T10 | D | A8 | Provider slowness exhausts Core threads | M | H | Core never calls providers synchronously; all via outbox + worker pools | Architecture test + failure tests |
| T11 | D | DF1 | Brute force / API flooding | H | M | Keycloak brute-force detection; API rate limits per identity/IP; proxy request limits | Phase 9 rate-limit tests |
| T12 | D | DF5 | Broker outage loses commands | M | H | Transactional outbox | Failure test (RabbitMQ down) |
| T13 | E | A6 | Privilege escalation by combining roles | M | H | SoD preventive checks on role assignment; built-in roles non-editable; detective SoD reports | SoD tests |
| T14 | E | Policy | Policy evaluation error treated as allow | L | H | Evaluator returns DENY on any exception; property tests; no default-allow code path | Policy tests |
| T15 | E | Keycloak | Keycloak admin or claims manipulation grants platform roles | M | H | Roles never read from token claims; platform DB authoritative (G2) | Architecture test on claim usage |
| T16 | S | DF2 | Keycloak outage used to bypass authentication | L | H | Fail closed: no new logins; no local fallback unless the break-glass admin is approved (Q-15) | Failure test (Keycloak down) |
| T17 | I | DF4 | Vault outage leads developers to add plaintext caching | M | H | Normative rule G3; code review + Semgrep rule forbidding secret caches; failure test | CI + failure test |
| T18 | T | Supply chain | Malicious dependency or image | M | H | Pinned versions, SCA, Trivy, SBOM, update bot with CI gate, signed images later | CI |
| T19 | E | Containers | Container escape via privileged config | L | H | ADR-0009 hardening; CI check rejecting `privileged`, socket mounts, host network | Compose policy check in CI |
| T20 | I | Backups | Backup files expose governance data | M | M | Encrypted backups; restricted storage; restore tests in isolated environment | Phase 10 |
| T21 | S | Emergency | Break-glass misused as a routine path | M | H | Emergency workflow: justification, MFA, dual control, short duration, recording, rotation, post-review; alerts on every use | Phase 4/6 tests |
| T22 | T | Time | Clock manipulation affects expiry/audit ordering | L | M | UTC; host NTP; monotonic sequence per audit partition; grants checked server-side | Phase 2 |

## 4. Residual risks and follow-ups

- Q-15 (local break-glass admin) decides T16's residual risk.
- Gateway and agent threat models are mandatory entry criteria for Phase 6.
- Penetration test in Phase 9 revalidates T01, T02, T04, T08, T11.
