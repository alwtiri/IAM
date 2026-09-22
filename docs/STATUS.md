# Project status: what works, what is partial, what remains

> The authoritative per-feature state is the maturity matrix in [FEATURE-MATURITY.md](FEATURE-MATURITY.md). Items below describe
> capabilities; none is TESTED/ACCEPTED until verified on the server.

Last updated: 2026-09-22 (Phases 6.2–12). "Works in the UI" means an administrator can do it end to end in the web app without
curl. Everything below is also available through the REST API.

## Works in the UI (after applying the latest bundle and running update-stack.sh)

| Feature | What the administrator can do | How to verify |
|---|---|---|
| Sign-in with MFA | Keycloak login, step-up (password + TOTP) for sensitive actions | Log in; register a provider (asks for step-up) |
| Dashboard | Live KPIs (servers, accounts, privileged accounts, open findings, identities, operations needing attention), recent operations, findings by type, platform health | Open `/` |
| User administration | Add a user (person + identity, optional immediate activation), search, activate / suspend / reinstate / disable with reason, grant and revoke roles (global or org-unit scope), create the Keycloak login with an e-mail invitation (set password + enrol MFA), resend the invitation; suspending/disabling an identity also blocks its Keycloak login | Identity & Access → Users |
| Server management (Linux) | Add a server, add an SSH connection (key stored in Vault only, host key pinned), test connection, discover accounts, see privileged accounts and findings, disable / enable / unlock / refresh an account with read-back verification and an audit reason | Assets → Servers; or `deploy/compose/scripts/smoke-phase3.sh` against the lab server |
| Access requests and approvals | Request a role for a period with justification; see which approvals the policy needs; managers and security administrators approve (MFA) or reject with a reason; access is granted automatically and ends automatically | Identity & Access → Access Requests / Approvals |
| Policies and SoD | View access policies (deny overrides allow), enable/disable them; view separation-of-duties rules | Identity & Access → Access Policies |
| Windows servers | WinRM (HTTPS) connection, discovery of local accounts, verified disable/enable/unlock | Assets → Servers |
| Databases (PostgreSQL) | Add a database, connect with TLS (password in Vault), test, discover login roles, see superusers and privileged memberships, disable/enable logins with read-back | Assets → Databases |
| Password vault (PAM) | "Vault" a privileged account: the platform sets a random 24-character password it alone knows (Linux, Windows, AD, PostgreSQL), promotes it only after the target confirmed it, rotates every 30 days, and shows VERIFIED / UNKNOWN / FAILED honestly | Accounts → Privileged Accounts → Vault; Privileged Access → Password Vault |
| Credential checkout | Request a password for 1–72 h with a justification (policy P-300: PAM administrator approves); or check out directly as a credential manager (MFA + reason); show the password (MFA, audited, hidden after 60 s); check in → the password is changed automatically; overdue checkouts end automatically | Privileged Access → Credential Requests / My Checkouts / Approvals |
| Emergency access | Mark vaulted accounts as emergency accounts; break glass without approval for 4 h (MFA + reason); security administrators e-mailed at once; every use reviewed by a second person; password rotated afterwards | Emergency Access → Break Glass / Active Emergencies / Reviews |
| Edit and delete | Servers and databases (edit, delete = decommission), connections (edit, disable/enable, delete), org units (rename), vault (remove) | Assets → Servers → Edit / Delete |
| Audit views | Access logs, privileged activity, configuration changes | Audit & Compliance |
| Settings and integrations | Effective non-secret configuration; SIEM forwarding of the audit trail (HMAC-signed HTTPS webhook) | Administration → System Settings / Integrations |
| Weekly security report | E-mail every Monday 07:00 to security and platform administrators | Automatic (`iam.reports.weekly-cron`) |
| Operations | Backup/restore scripts, acceptance script, runbook, HA guide | `deploy/compose/scripts/`, `docs/operations/RUNBOOK.md` |
| Look & feel | shadcn/ui-style design, light and dark mode, Arabic RTL | Header: moon/sun button, العربية |
| Reports | CSV exports: accounts, privileged accounts, open findings, access requests, users, audit trail | Reports |
| Scheduled discovery | Every bound server/database is re-discovered daily (configurable `iam.discovery.interval`) | Automatic |
| Notifications | E-mail to approvers when a request waits for them and to requesters on the outcome | Mailpit in development |
| Organization units | View the tree and add units | Identity & Access → Directory |
| Accounts views | All, privileged, Linux, Windows accounts with the same actions | Accounts → … |
| Audit | Tamper-evident audit log of every change | Audit & Compliance → Audit Logs |
| Health | Component health with affected functionality | Administration → System Health |

## Partial

| Feature | What is missing | Planned |
|---|---|---|
| Windows authentication | WinRM uses Basic over HTTPS; NTLM/Kerberos not yet | When required by the real estate |
| Org-unit management screen | API exists; UI uses the list only | With user administration polish |
| Container image CVE gate | Report-only until Phase 9 (ADR-0019) | Phase 9 |

## Not in this release (explicit scope decisions)

| Item | Why / plan |
|---|---|
| Privileged session gateway (SSH/RDP proxy with recording) | Needs a dedicated gateway component (terminal streaming, recording storage); checkouts cover password-based access today |
| More providers (MySQL, Oracle, SQL Server, network devices, VMware, storage) | Provider SPI is ready; each needs its own provider module and lab |
| Groups/entitlements management screens | Entitlements are discovered and shown per account; editing follows with the next provider wave |
| Policy authoring UI, access reviews (certification campaigns) | Policies are seeded and can be enabled/disabled; authoring and campaigns are the next governance increment |
| HR/ITSM connectors | Events are published on `iam.events` (identity lifecycle, emergency access) for connectors to consume |
| Production TLS, HA deployment, image signing | Documented in `docs/phase-10/HA-AND-SCALE.md` and ADR-0022; depend on the target infrastructure and registry |
