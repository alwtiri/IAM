# ADR-0011: CI platform: GitHub Actions (GitLab CI kept portable)

- **Status:** Accepted (Phase 1) — revisit on answer to Q-06
- **Date:** 2026-09-21

## Context

The repository is hosted on GitHub. Q-06 (GitLab vs GitHub) is unanswered. The spec allows GitLab CI, GitHub Actions, or equivalent.

## Decision

Use GitHub Actions for the Phase 1 CI foundation. Keep pipeline logic in the build tools (Gradle tasks, pnpm scripts, shell scripts under `ci/`) so that a `.gitlab-ci.yml` can call the same entry points if the organization prefers GitLab. Pipeline stages: build → unit & architecture tests → integration tests (Testcontainers) → security scans (Gitleaks, Semgrep, OWASP Dependency-Check, Trivy fs/config/image) → image build (no push until a registry is decided).

## Consequences

+ Works immediately on the current host.
- If the organization is air-gapped (Q-04/Q-05), self-hosted runners and an internal artifact mirror are required.
