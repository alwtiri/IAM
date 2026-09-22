# Phase 4 gate report: access requests, approvals, policies, SoD

| Field | Value |
|---|---|
| Branch | `phase-3-core-providers` (Phase 4 continues on the same branch to keep one update path for the owner) |
| Date | 2026-09-22 |
| Decision mode | Owner asked for autonomous progress; decisions are listed in §4 |

## 1. Delivered

| Area | Content |
|---|---|
| Data | Migration V12: `policy.policy` (3 seeded policies), `sod.rule` (5 seeded rules), `request.access_request`, `request.approval_step`, permissions `policy:read/write`, `sod:read`, `request:read` |
| Policy engine | Pure deny-overrides evaluator (ADR-0013), fail closed, enable/disable with step-up and audit |
| SoD | Preventive and detective role conflicts, checked at submission and before the grant |
| Requests | Self-service role requests with scope (own unit or platform), duration, justification; sequential approvals (manager → security administrators for privileged roles); cancel; automatic time-bound grant; status follows expiry/revocation |
| UI | Access Requests (request form shows the approvals the policy will need), Approvals (approve with MFA, reject with reason), Policies & SoD page, dashboard KPI "waiting for my approval" |
| Tests | Evaluator and request service unit tests (8 scenarios), UI tests for request and approval flows |

## 2. Seeded policies

| Code | Effect | Scope | Approvals | Max |
|---|---|---|---|---|
| P-100 | ALLOW | any role | manager | 180 days |
| P-200 | ALLOW | platform, security, PAM, infrastructure, IAM administrator | manager → security administrator | 30 days |
| P-900 | DENY | platform/security administrator for contractors and external identities | — | — |

## 3. How to verify (after `update-stack.sh`)

1. Create a user with a manager (Users → Add user; set the manager on the person when the person edit screen arrives — until then the step routes to IAM administrators, which is visible on the step).
2. Sign in as that user (login invitation from Mailpit), Access Requests → Request access → HELPDESK, 30 days.
3. Sign in as the approver → Approvals → Approve (MFA prompt) → the requester now holds HELPDESK until the shown date.
4. Request PLATFORM_ADMINISTRATOR for 90 days → rejected immediately (P-200 maximum 30 days).

## 4. Decisions taken without owner input

| Decision | Reason |
|---|---|
| Approval steps stored with the request (ADR-0020) | Avoids a module cycle; one workflow type today |
| Self-service requests only in Phase 4 | Smallest complete flow; on-behalf requests reuse it |
| Fallback routing to IAM / platform administrators | A request must never wait for a non-existent approver |
| Emergency access and PAM request pages moved to Phase 6 | They depend on sessions/gateways and credential checkout |

## 5. Not in this phase

- Policy authoring UI (create/edit), access reviews/certification campaigns, joiner/mover/leaver automation, requests for server accounts.
