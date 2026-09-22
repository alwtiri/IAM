# ADR-0022: Phase 9 security gates

Status: Accepted, 2026-09-22 · Supersedes the report-only period of ADR-0019

## Decision
1. **Image scan blocks the build.** Trivy fails CI on fixable HIGH/CRITICAL findings in iam-core, iam-worker and
   iam-web (`ignore-unfixed: true`). Accepted exceptions live in `.trivyignore`, one CVE per line with an expiry date
   and a reason, reviewed every release.
2. **SBOM.** CI produces a CycloneDX SBOM for the images and keeps it as a build artifact. Signing (cosign) follows
   once a registry is chosen (Q-05).
3. **Edge hardening.** The proxy adds `Permissions-Policy` and `Cross-Origin-Opener-Policy` to the existing
   nosniff / frame / referrer headers (the web image sets a strict CSP), hides the nginx version, and rate-limits
   the login endpoints (10 r/s, burst 20) and the API (50 r/s, burst 100) per client address, answering 429.
4. **Audit leaves the host.** The audit trail can be forwarded to a SIEM over HTTPS with HMAC-SHA256 signatures
   (`IAM_SIEM_WEBHOOK_URL`, `IAM_SIEM_WEBHOOK_SECRET`), at-least-once and in order.
5. **Acceptance script.** `deploy/compose/scripts/acceptance-check.sh` verifies services, edge controls, rate
   limiting, secret hygiene (git and logs) and the presence of backup tooling without logging in.

## Consequences
A new fixable HIGH/CRITICAL in a base image stops the pipeline until the base image is updated or an exception with an
expiry is recorded. Rate limits apply per source address; a shared NAT in front of many administrators may need
higher values (`deploy/compose/proxy/default.conf`).
