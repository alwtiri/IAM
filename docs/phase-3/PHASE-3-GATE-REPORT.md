# Phase 3 gate report: core providers, worker plane, server and user management

| Field | Value |
|---|---|
| Branch | `phase-3-core-providers` |
| Date | 2026-09-22 |
| Decision mode | Owner asked for continuous progress; this report is reviewable at any time and work continues with Phase 4 |

## 1. Delivered

| Increment | Content |
|---|---|
| 3.1 | Account management domain (accounts, entitlements, discovery runs, findings), migrations V9–V11, permissions |
| 3.2 | Operation pipeline to the worker plane: outbox → RabbitMQ per provider type, results/progress, deadlines, one in-flight mutation per account, single-use credential handles redeemed over internal mTLS |
| 3.3 | Worker runtime: per-pool listeners, circuit breaker and bulkhead per instance, deadline enforcement, ServiceLoader plugins |
| 3.4 | linux-ssh provider (MINA SSHD, host-key pinning, sudo -n least privilege, verified disable/enable/unlock) |
| 3.5 | active-directory provider (UnboundID, LDAPS/StartTLS, paged discovery, verified disable/enable/unlock, krbtgt protected) |
| 3.6 | generic-rest provider (SCIM 2.0) |
| 3.7 | windows-winrm provider (WS-Management over HTTPS, PowerShell LocalAccounts, parameters base64-bound, XXE-safe parsing, built-in Administrator and own service account protected) |
| 3.8 | UI: modern dashboard, servers (Linux SSH and Windows WinRM connections, test connection, discovery, accounts with verified actions), accounts views, user administration (add user, lifecycle, roles, Keycloak login creation with invitation), organization units; `smoke-phase3.sh`; lab Linux server |

## 2. Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Capabilities declared; unsupported calls explained | Met | Provider contract tests (testkit) for all four providers |
| Discovery imports without modifying targets | Met | Discovery scripts are read-only; `smoke-phase3.sh` step 5 |
| Enable/disable/unlock idempotent and verified; SUCCESS only after read-back | Met | Provider unit tests (no second change, UNKNOWN on failed read-back); DB constraint from Phase 2; smoke step 6 |
| Create, reset, rotate | Deferred | Declared UNSUPPORTED with explanation: provisioning is Phase 4 fulfilment, password operations follow with credential management |
| Linux, Windows local and application providers work with AD disabled | Met | Providers are independent plugins and queues |
| Unreachable provider opens its circuit without affecting others | Met (unit level) | `CircuitBreakerTest`, per-type queues; runtime chaos test planned for Phase 10 |

## 3. Verification status

| Check | Status |
|---|---|
| `ci/local-build.sh` on the owner's server | Passed up to part 5; later parts to be confirmed with the combined update |
| Worker bring-up with providers | Passed (linux-ssh, generic-rest, active-directory loaded; healthy) |
| Internal mTLS listener | Passed (8443, client certificate required) |
| `smoke-phase3.sh` (lab Linux) | To run with the combined update |
| Windows lab run | Needs a Windows VM with a WinRM HTTPS listener; manual checklist item |
| AD lab run | Needs a domain controller; manual checklist item |

## 4. Decisions taken without owner input

| Decision | Reason |
|---|---|
| Image CVE gate report-only until Phase 9 (ADR-0019) | Owner request to stop losing time on third-party CVEs |
| WinRM uses Basic over HTTPS only (no NTLM/Kerberos yet) | Smallest secure option; NTLM/Kerberos are added when the real estate requires it |
| Keycloak login creation through a dedicated `iam-core-admin` client | Least privilege: only manage/view/query users |
| Proxy resolves container names per request | Recreated containers change IPs; caused a blank login page |

## 5. Known limitations

- Password reset/rotation and account creation are not in Phase 3.
- Discovery is manual (scheduled discovery and drift: Phase 8).
- WinRM NTLM/Kerberos authentication is not implemented.
