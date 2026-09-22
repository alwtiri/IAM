# Global Development & Git Workflow Policy

| Field | Value |
|---|---|
| Status | **Adopted** — owner directive, 2026-09-22 |
| Applies to | The entire project, Phase 0 through Phase 12 |
| Decision record | [ADR-0017](../adr/ADR-0017-local-first-development-workflow.md) |

GitHub must not become a performance bottleneck or a runtime dependency for development. The project follows a **local-first development workflow**.

## 1. Local-first principle

Normal development operations run against the local repository and working tree:

```text
Local Working Tree → Edit / Implement → Local Build → Local Unit / Integration Tests
→ Local Validation → Local Commit → Checkpoint → GitHub Push
```

GitHub is not inserted into every development iteration. There is no synchronization with GitHub merely because a file changed or a small task was completed.

## 2. GitHub role

GitHub is remote source control, remote backup, collaboration, pull-request and code-review platform, CI/CD platform, release history, and repository protection.

GitHub is **not** a runtime dependency, a development dependency for every operation, a required source for every local build, a required destination after every change, or a synchronization point after every file modification. The application itself never depends on GitHub being available.

## 3. Development continues if GitHub is unavailable

If GitHub is unreachable, slow, rate-limited, temporarily unavailable, or affected by DNS, network, or authentication problems, local development continues wherever technically possible. The connectivity issue is reported, but unrelated local work does not stop.

## 4. Avoid excessive remote operations

No unnecessary repeated `git fetch`, `git pull`, or `git push` during normal implementation. In particular, do not:

- fetch after every file change;
- push after every commit;
- wait for GitHub after every step;
- repeatedly query remote branches or GitHub APIs;
- trigger unnecessary CI runs;
- re-synchronize the same branch without a reason.

Remote synchronization happens at logical checkpoints.

## 5. Local commits

Local commits are meaningful recovery points that represent logical units of work, for example `phase2-identity-foundation` or `phase3-linux-provider`. Messages follow the project convention. Meaningless messages (`update`, `fix`, `test`, `changes`, `again`, `final`, `final2`) are not used without a legitimate reason.

## 6. Push policy

Push at logical checkpoints, not after every change. Appropriate points:

- completion of a major architectural milestone or a meaningful feature;
- completion of a phase;
- before a risky migration, a large refactoring, or an environment transition;
- before a release candidate;
- when a remote backup is intentionally required.

Fewer meaningful pushes are preferred over continuous synchronization.

## 7. Pull / fetch policy

Before starting a new major phase, the remote status may be checked with `git status`, `git branch -vv`, and `git log --oneline --decorate -10`. If synchronization is required, run `git fetch origin` and inspect the differences before deciding on pull, merge, or rebase. Never run `git pull` blindly without understanding the local and remote state.

## 8. No destructive Git operations without explicit authorization

`git reset --hard`, `git clean -fd`, `git push --force`, `git push --force-with-lease`, `git rebase`, `git branch -D`, or equivalents are never performed automatically.

Local work, existing commits, branch history, uncommitted changes, and recovery points are preserved. If such an operation appears necessary, stop and request explicit approval.

## 9. Branch safety

Always verify `git branch --show-current` and `git status` before significant Git operations.

- Never assume the current branch.
- Never silently switch branches.
- Never overwrite another branch.
- Never merge unrelated branches merely to resolve a local development issue.

## 10. CI/CD policy

CI is a validation and delivery mechanism, not the primary feedback loop:

```text
Local Build → Local Tests → Local Security Checks → Local Validation → Git Commit → GitHub Push → CI Validation
```

Do not wait for CI on every small iteration when equivalent local validation exists. CI remains mandatory at defined gates and release checkpoints.

## 11. Offline / degraded development

Dependencies are obtained and cached locally where practical: Maven or Gradle, npm, Docker images, security databases, and build artifacts. They are not downloaded repeatedly because a remote service is slow.

Source-control connectivity is distinct from build-dependency connectivity. A GitHub outage does not imply that local development must stop.

## 12. Docker development

```text
Local Source → Local Docker Build → Local Container Tests → Local Validation
```

Images are pushed to a registry only at appropriate checkpoints, never for every local iteration.

## 13. Security scanning

Where tools and databases are available locally, run the scans locally first: Semgrep, Gitleaks, Trivy, dependency scanning, OWASP ZAP where appropriate, and SBOM generation. Remote CI scanning is an additional validation layer, and no local check depends on GitHub.

## 14. Phase gates

```text
Implementation → Local Build → Local Tests → Security Validation → Architecture Validation
→ Git Commit → Phase Gate Report → Explicit Approval → GitHub Push / Remote Checkpoint → Next Phase
```

Work stops at every phase gate until explicit approval is given.

## 15. Recovery points

Recoverable local Git history is always maintained. Create a local checkpoint before risky operations, such as:

- database migrations or large schema changes;
- security-architecture changes or an authentication redesign;
- provider SPI changes;
- PAM gateway refactoring;
- major dependency upgrades.

## 16. GitHub connectivity troubleshooting

If GitHub operations become slow or fail, diagnose instead of retrying:

```bash
git remote -v
git branch -vv
git status
git config --get remote.origin.url
git config --get http.version
getent hosts github.com
curl -I https://github.com
```

For SSH remotes, verify SSH connectivity separately (`ssh -T git@github.com`). A failing remote operation is not retried until its cause is identified.

## 17. No hidden remote dependency

The implementation never assumes that GitHub, its API, GitHub Actions, or GitHub authentication is always available, or that the remote repository is always synchronized. Architecture and development process remain functional while GitHub is unavailable.

## Global rule

**Develop locally first. Validate locally first. Commit locally first. Synchronize remotely at meaningful checkpoints.**

The project optimizes for fast local iteration, safe Git history, controlled remote synchronization, and reliable CI validation. Architecture, security, testing, and traceability are never sacrificed for speed. Development speed is never sacrificed by making GitHub part of every iteration.

## Project tooling that implements this policy

| Need | Tool |
|---|---|
| Local security and policy checks, before committing | `ci/local-checks.sh` (Semgrep project rules, contract/enum sync, compose hardening policy, shell lint; plus the registry Semgrep packs, Gitleaks, and Trivy when installed or reachable) |
| Local recovery point | `git commit` with a meaningful message; no push |
| Checkpoint push (phase gate, milestone) | `scripts/git-push.sh "message" [branch]`: shows the branch and status, blocks secret files, commits, pushes once, and prints the PR link |
| Phase-2 runtime validation | `deploy/compose/scripts/smoke-phase2.sh` |
