# Phase 6.1 gate report — privileged credential vault and checkout

Date: 2026-09-22 · Branch: `phase-3-core-providers` · Decision record: ADR-0021

## Delivered
| Area | Result |
|---|---|
| Provider rotation | `ROTATE_PASSWORD` in linux-ssh (chpasswd on stdin, verified by change date), windows-winrm (Set-LocalUser, verified by PasswordLastSet), active-directory (unicodePwd over TLS only, verified by pwdLastSet), postgresql (SCRAM-SHA-256 verifier computed locally, verified by login test). Service accounts, krbtgt and superusers are protected. |
| Vault | Take-over rotates immediately; pending Vault version promoted only on SUCCESS; FAILED keeps the old password; UNKNOWN keeps both and is retried hourly; interval rotation (default 30 days). |
| Checkout | Exclusive, 1–72 h, holder-only reveal with step-up and `no-store`, every reveal audited; check-in/expiry/revoke rotate when revealed. |
| Requests | Type CREDENTIAL in the access-request engine; policies P-300 (PAM administrator approval, ≤ 1 day, justification) and P-910 (deny external identities). |
| Permissions | `credential:manage` (PLATFORM_ADMINISTRATOR, PAM_ADMINISTRATOR). |
| API | 10 endpoints, OpenAPI updated (Redocly valid). |
| UI | Password Vault, Credential Requests, My Checkouts, PAM Approvals; "Vault" action on privileged accounts; shadcn-style redesign with dark mode. |

## Verification in this environment
- Unit tests: core 135 pass (new: CredentialVaultServiceTest ×5, credential request flow), providers: Linux 11, Windows 11, AD 12, PostgreSQL 9 (SCRAM verifier checked against an independent derivation).
- Web: 17 tests pass, type check clean, screenshots reviewed (light, dark, vault).
- Spring wiring, Flyway V13 and the full build are verified by `update-stack.sh` on the server (not compilable here).

## Open / next
- End-to-end test on the lab (Linux lab server, lab-postgres) — see the step-by-step test guide.
- Phase 6.2: SSH gateway with session recording; emergency accounts.
