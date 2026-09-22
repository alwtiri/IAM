# ADR-0017: Local-first development workflow with checkpoint-based remote synchronization

| Field | Value |
|---|---|
| Status | Accepted (owner directive, 2026-09-22) |
| Scope | All phases (0–12) |
| Normative text | [docs/process/GIT-WORKFLOW-POLICY.md](../process/GIT-WORKFLOW-POLICY.md) |

## Context

During the Phase 2 gate, changes moved to GitHub after each small fix, via bundles and pull requests. This made GitHub part of every iteration. CI then became the first place some problems were found: an unresolvable Trivy action tag, and Semgrep findings. Those problems could have been caught locally. The owner issued a global policy that GitHub must be neither a performance bottleneck nor a runtime or development dependency.

## Decision

1. **Local-first loop.** Development follows edit → local build → local tests → local validation → local commit. Pushes happen only at logical checkpoints: a phase gate, a milestone, before risky changes, or before a release candidate.
2. **Local checks before CI.** `ci/local-checks.sh` runs every check that works offline: project Semgrep rules, contract/enum sync, the compose hardening policy, and shellcheck. It also runs the registry Semgrep packs, Gitleaks, and Trivy when they are available. CI stays mandatory at gates and release checkpoints.
3. **No destructive Git operations** without explicit owner approval: `reset --hard`, `clean -fd`, force push, `rebase`, `branch -D`, or equivalents. Branch and status are verified before significant Git operations.
4. **No hidden remote dependency.** Neither the application nor the build requires GitHub at runtime. Build dependencies (Gradle, npm, images, scanner databases) are cached locally where practical.
5. **Pinned inputs.** Base images are pinned by digest and third-party CI actions by commit SHA. A moved or deleted upstream tag therefore cannot break or silently change a build.

## Consequences

- Fewer, meaningful pushes and CI runs. CI failures become exceptions rather than the feedback loop.
- The agent reports GitHub connectivity problems instead of retrying and continues local work.
- Digest and SHA pins need periodic updates. A dependency-update bot is planned for Phase 9, and until then updates are manual at checkpoints.
