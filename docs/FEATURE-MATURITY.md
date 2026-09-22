# Feature maturity matrix

Updated: 2026-09-22. Scale: NOT STARTED · IN DESIGN · IN DEVELOPMENT · PARTIALLY IMPLEMENTED · FUNCTIONALLY COMPLETE · TESTED · ACCEPTED.
"Functionally complete" means every primary workflow is implemented end to end (API, authorization, validation, audit,
UI, unit/component tests) but has **not yet been verified on the server** (`update-stack.sh` + the acceptance list).
Nothing is marked TESTED or ACCEPTED until that verification is reported back.

| Feature | State | Completed | Missing | Action |
|---|---|---|---|---|
| **Users** | FUNCTIONALLY COMPLETE | Server-side search (username/name/e-mail), filters (state, type, org unit), sort, paging; one-step create (atomic, duplicate and format checks); profile view and edit (optimistic locking, manager-cycle check); activate / lock / unlock / deactivate with reason (self-change blocked); create/link login, password & MFA reset e-mail; grant/revoke roles; effective permissions; activity from the audit trail; buttons shown only with the matching permission | Hard delete (by policy: deactivation keeps history); sign-in sessions and last-login time (**Not implemented** — Keycloak owns sessions) | Verify on the server |
| Servers (Linux) | PARTIALLY IMPLEMENTED | List, create, edit (name, host, environment, criticality, unit), delete (decommission, connections retired), SSH connection add/edit/disable/delete, test, discovery, accounts with lifecycle | Tags, owner / technical owner in the UI; capabilities and health per server; entitlements view; explicit "Not applicable" for sessions | **Next feature to complete** |
| Servers (Windows) | PARTIALLY IMPLEMENTED | Same UI as Linux with WinRM connection; provider disable/enable/unlock/rotation | Verification against a real Windows host | After Linux servers |
| Databases (PostgreSQL) | PARTIALLY IMPLEMENTED | Same as servers with TLS connection, discovery, login enable/disable, rotation | Same gaps as servers | With servers |
| Active Directory | PARTIALLY IMPLEMENTED | Provider (discovery, disable/enable/unlock, rotation) | No UI to register an AD domain | Planned after servers |
| Other assets (VMware, storage, network, applications) | NOT STARTED | — | Providers and screens | Deferred |
| Accounts | PARTIALLY IMPLEMENTED | Lists (all, privileged, Linux, Windows, service), enable/disable/unlock/refresh with verification, vault take-over | Governance edit (owner, type, exclusion) and finding resolution in the UI (API exists) | Complete after servers |
| Password vault & checkout | FUNCTIONALLY COMPLETE | Take-over, verified rotation, schedule, checkout (direct / requested), reveal with MFA, check-in, remove from vault | — | Verify on the server |
| Emergency access | FUNCTIONALLY COMPLETE | Mark emergency, break glass, notification, second-person review | — | Verify on the server |
| Access requests & approvals | FUNCTIONALLY COMPLETE | Role and credential requests, policy decision, SoD, sequential approvals with MFA, fulfilment and expiry, e-mail | — | Verify on the server |
| Roles | PARTIALLY IMPLEMENTED | Built-in roles with permissions (view), assignments grant/revoke | Custom role create/edit/delete | Planned |
| Policies / SoD | PARTIALLY IMPLEMENTED | View, enable/disable policies; view SoD rules | Authoring | Planned |
| Org units | PARTIALLY IMPLEMENTED | Tree view, create, rename | Move, delete (needs dependency checks) | Planned |
| Groups / entitlements | NOT STARTED | Entitlements discovered per account only | Management screens | Deferred |
| Providers (admin page) | PARTIALLY IMPLEMENTED | List of connections; full management from the server page | Standalone create/edit on the Providers page | Planned |
| Privileged sessions (SSH/RDP gateway) | NOT STARTED | — | Gateway, recording | Deferred |
| Audit | PARTIALLY IMPLEMENTED | Tamper-evident log, verification (MFA), preset views (access, privileged, configuration), per-user activity, SIEM forwarding | Free-form search form (actor, date range, action) in the UI | Planned |
| Reports | FUNCTIONALLY COMPLETE | CSV exports, weekly security e-mail | — | Verify on the server |
| Settings / integrations | FUNCTIONALLY COMPLETE (read-only by design) | Effective settings, SIEM status | Editing settings in the UI (configuration stays in deployment files by decision) | — |
| Operations (backup, acceptance, runbook) | FUNCTIONALLY COMPLETE | Scripts and documents | Restore rehearsal on a spare host | Run once |
