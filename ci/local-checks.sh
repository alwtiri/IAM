#!/usr/bin/env bash
# Local validation before committing (ADR-0017, GIT-WORKFLOW-POLICY §10/§13).
# Runs everything that works offline; network-dependent scanners run only when available.
#   ./ci/local-checks.sh            # all checks
#   SKIP_REGISTRY=1 ./ci/local-checks.sh   # never contact semgrep.dev (offline)
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

PASS=0; FAIL=0; SKIP=0
run()  { local name="$1"; shift; if "$@" >/tmp/iam-check.log 2>&1; then echo "  PASS  $name"; PASS=$((PASS+1)); else echo "  FAIL  $name"; sed 's/^/        /' /tmp/iam-check.log | tail -25; FAIL=$((FAIL+1)); fi; }
skip() { echo "  SKIP  $1 ($2)"; SKIP=$((SKIP+1)); }
have() { command -v "$1" >/dev/null 2>&1; }

echo "Branch: $(git branch --show-current)   $(git status --porcelain | wc -l | tr -d ' ') uncommitted change(s)"

echo "Policy and contracts"
run "contract <-> Java enum sync" python3 ci/check-contract-sync.py
compose_policy() { docker compose -f deploy/compose/compose.yaml --env-file deploy/compose/.env.example config --format json \
  | python3 ci/check-compose-policy.py; }
if have docker && docker compose version >/dev/null 2>&1; then
  run "docker compose config + hardening policy" compose_policy
else skip "compose hardening policy" "docker compose not installed"; fi

echo "Static analysis"
if have semgrep; then
  run "Semgrep project rules" semgrep scan --error --metrics=off --disable-version-check --config ci/semgrep/iam-rules.yaml --exclude deploy/compose/lab
  if [ "${SKIP_REGISTRY:-0}" != 1 ] && curl -sfI --max-time 5 https://semgrep.dev >/dev/null 2>&1; then
    run "Semgrep registry packs (as CI)" semgrep scan --error --metrics=off --disable-version-check \
      --config p/java --config p/typescript --config p/dockerfile --config p/secrets --exclude deploy/compose/lab
  else skip "Semgrep registry packs" "semgrep.dev not reachable"; fi
else skip "Semgrep" "pip install semgrep"; fi
if have shellcheck; then
  run "shellcheck scripts" shellcheck -S warning scripts/*.sh deploy/compose/scripts/*.sh ci/*.sh
else skip "shellcheck" "not installed"; fi

echo "Secrets and vulnerabilities"
if have gitleaks; then run "Gitleaks (working tree)" gitleaks dir --no-banner --config .gitleaks.toml .
else skip "Gitleaks" "not installed"; fi
if have trivy; then run "Trivy filesystem + IaC (HIGH,CRITICAL)" trivy fs --quiet --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,misconfig,secret --skip-dirs deploy/compose/lab .
else skip "Trivy" "not installed"; fi

echo
echo "Result: $PASS passed, $FAIL failed, $SKIP skipped"
[ "$FAIL" = 0 ]
