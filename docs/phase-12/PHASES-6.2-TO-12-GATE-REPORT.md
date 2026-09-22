# Gate report — Phases 6.2 to 12 (development increment, not accepted)

> Status per feature: see ../FEATURE-MATURITY.md. Everything below is at most FUNCTIONALLY COMPLETE; server verification
> and the acceptance list are pending. Phases 10–12 deliver documents and scripts, not a verified HA or acceptance run.

Date: 2026-09-22 · Branch: `phase-3-core-providers`

| Phase | Delivered | Evidence |
|---|---|---|
| 6.2 Emergency access | Emergency flag on vaulted accounts, break-glass (4 h, MFA, reason ≥ 10 chars), notification + `iam.events` event, second-person review, rotation afterwards; permissions `emergency:access`, `emergency:review` (V14) | `CredentialVaultServiceTest.breakGlass…`; Emergency pages |
| 7 Reporting & settings | Access logs, privileged activity, configuration changes (audit search with prefixes and type lists), system settings endpoint and page, weekly security e-mail | OpenAPI `/system/settings`; notification template `report.weekly` |
| 8 Integrations | SIEM forwarder (ordered, at-least-once, HMAC-SHA256, cursor in `audit.forwarding_state`), integrations page; existing `iam.events` exchange carries identity and emergency events | `SiemForwarder`; compose `IAM_SIEM_WEBHOOK_URL` |
| 9 Hardening | Blocking image scan with expiring exceptions, CycloneDX SBOM, extra security headers, edge rate limits (login/API), acceptance script | ADR-0022; `nginx -t` OK; shellcheck clean |
| 10 HA & scale | HA topology and sizing guide | `docs/phase-10/HA-AND-SCALE.md` |
| 11 Operations | Runbook, backup/restore (DBs, Vault Raft snapshot, secrets, checksums, retention) | `docs/operations/RUNBOOK.md`, `scripts/backup.sh`, `scripts/restore.sh` |
| 12 Acceptance | 18-step functional list + automated edge/hygiene checks | `docs/phase-12/ACCEPTANCE.md`, `scripts/acceptance-check.sh` |

Also in this release: full edit/delete for servers, databases, connections, org units and vault entries; fix of the
accounts SQL defect that broke account lists.

Verification here: core unit tests 137 pass; web 17 pass and type-check clean; OpenAPI valid; proxy config validated with
`nginx -t`. The Spring build, Flyway V14 and the UI flows are verified on the server with `update-stack.sh`,
`acceptance-check.sh` and the acceptance list.
