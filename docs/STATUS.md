# Project status: what works, what is partial, what remains

Last updated: 2026-09-22 (Phase 6.1). "Works in the UI" means an administrator can do it end to end in the web app without
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

## Remaining phases (Master Prompt)

| Phase | Scope |
|---|---|
| 4 (remaining) | Policy authoring UI, access reviews, joiner/mover/leaver, on-behalf and server-account requests |
| 5 (remaining) | More providers: MySQL/Oracle/SQL Server, network devices, virtualization, storage |
| 6 (remaining) | Privileged sessions through SSH/RDP gateways with recording, emergency (break-glass) accounts |
| 7 (remaining) | Scheduled/emailed reports, access logs, configuration history, settings |
| 8 (remaining) | Drift detection and reconciliation, SIEM/ITSM/HR integrations |
| 9 | Security hardening: image gate back on, SBOM, signing, production settings |
| 10–12 | HA / scale, operations runbooks, final acceptance |
