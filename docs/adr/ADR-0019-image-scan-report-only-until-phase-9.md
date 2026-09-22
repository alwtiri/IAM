# ADR-0019: Container image vulnerability scan is report-only until Phase 9

| Field | Value |
|---|---|
| Status | Accepted (owner decision, Phase 3) |
| Related | ADR-0017 (local-first workflow), Phase 9 (security hardening, SBOM, signing) |

## Context

Base-image and dependency CVEs are published continuously. During Phases 2–3 the blocking Trivy image gate failed repeatedly on newly published CVEs in third-party images and libraries (Boot BOM, Tomcat, amqp-client, Alpine). Each fix cost a full build/scan/push cycle on the dev server and delayed functional work (server and account management). The fixes so far are kept (Boot 4.0.8, Tomcat 11.0.25, amqp-client 5.34.0, Alpine updates in iam-web).

## Decision

- The CI image scans (iam-core, iam-worker, iam-web) keep running and printing their findings, but no longer fail the build (`exit-code: '0'`).
- `ci/local-image-scan.sh` stays available and is optional before a push.
- Still blocking: Semgrep (project rules, including secret handling), the Trivy filesystem/secret/IaC scan, and all tests.
- Phase 9 turns the image gate back on (together with SBOM, image signing and a documented CVE exception process). The environment is dev/lab only until then and is not exposed to production data.

## Consequences

- Images may carry known, fixable CVEs during Phases 3–8. The CI log lists them; no production deployment happens before Phase 9.
- Re-enabling the gate is a one-line change per scan step (`exit-code: '1'`), tracked in the Phase 9 checklist.
