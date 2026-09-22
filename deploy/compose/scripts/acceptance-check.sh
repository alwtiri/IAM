#!/usr/bin/env bash
# Final acceptance checks that need no login (Phase 12). Run from anywhere in the repo on the platform host.
# Complements the functional acceptance list in docs/phase-12/ACCEPTANCE.md (performed in the UI).
# shellcheck disable=SC2015  # ok/bad always succeed, so A && ok || bad is a safe if-then-else here
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
BASE="${BASE:-$(grep -E '^IAM_PUBLIC_URL=' .env 2>/dev/null | cut -d= -f2-)}"; BASE="${BASE:-http://127.0.0.1:8088}"
PASS=0; FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Platform: $BASE"
echo "1. Services"
for svc in postgres keycloak vault rabbitmq iam-core iam-worker iam-web iam-proxy; do
  id="$(docker compose ps -q "$svc" 2>/dev/null)"
  if [ -z "$id" ]; then bad "$svc not running"; continue; fi
  st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
  case "$st" in healthy|running) ok "$svc $st" ;; *) bad "$svc $st" ;; esac
done

echo "2. Edge security"
[ "$(code "$BASE/oauth2/authorization/keycloak")" = 302 ] && ok "login redirects to Keycloak" || bad "login redirect"
[ "$(code "$BASE/api/v1/me")" = 401 ] && ok "API requires a session (401)" || bad "API without session is not 401"
[ "$(code "$BASE/internal/v1/credentials/x")" = 404 ] && ok "internal API not routed from the edge" || bad "internal API reachable"
[ "$(code "$BASE/actuator/health")" = 404 ] && ok "actuator not routed from the edge" || bad "actuator reachable"
H="$(curl -s -D - -o /dev/null "$BASE/")"
for h in "X-Content-Type-Options: nosniff" "X-Frame-Options: DENY" "Referrer-Policy: no-referrer" "Content-Security-Policy" "Permissions-Policy"; do
  echo "$H" | grep -qi "^$h" && ok "header $h" || bad "missing header $h"
done
echo "$H" | grep -qi '^server: nginx/' && bad "nginx version disclosed" || ok "server version hidden"
n429=0; for _ in $(seq 1 80); do [ "$(code "$BASE/oauth2/authorization/keycloak")" = 429 ] && n429=$((n429+1)); done
[ "$n429" -gt 0 ] && ok "login endpoint is rate limited ($n429 of 80 rejected)" || bad "no rate limit on the login endpoint"

echo "3. Secrets"
git -C .. ls-files | grep -E '(^|/)secrets/[^.]|\.env$|vault-dev-init|\.bundle$' | grep -v '\.gitkeep\|README' && bad "secret files are tracked by git" || ok "no secret files tracked by git"
docker compose logs --no-color iam-core iam-worker 2>/dev/null | grep -Eiq 'BEGIN (RSA |OPENSSH )?PRIVATE KEY|password=[^*]' && bad "secret-like content in logs" || ok "no secret-like content in logs"

echo "4. Recovery"
[ -x scripts/backup.sh ] && [ -x scripts/restore.sh ] && ok "backup/restore scripts present" || bad "backup/restore scripts missing"

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
