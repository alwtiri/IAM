#!/usr/bin/env bash
# One-step update of the development stack after new commits were merged (run from anywhere inside the repo):
#   1. local build and tests (skip with --skip-build),
#   2. new development secrets and Keycloak settings (idempotent),
#   3. rebuild and restart iam-core, iam-worker, iam-web; restart the proxy,
#   4. health and login checks.
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"
SKIP_BUILD=0; [ "${1:-}" = "--skip-build" ] && SKIP_BUILD=1

echo "== Branch: $(git branch --show-current) @ $(git log --oneline -1)"
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Working tree has uncommitted changes to tracked files - commit or stash them first." >&2
  exit 1
fi

if [ "$SKIP_BUILD" = 0 ]; then
  echo "== Build and tests"
  ./ci/local-build.sh 2>&1 | tee /tmp/iam-local-build.log | grep -E "error:|tests completed|FAILED|BUILD" || true
  grep -q "BUILD SUCCESSFUL" /tmp/iam-local-build.log || { echo "Build failed - see /tmp/iam-local-build.log" >&2; exit 1; }
fi

cd deploy/compose
echo "== Secrets (existing ones are kept)"
./scripts/generate-dev-secrets.sh | grep -v "^kept" || true

echo "== Keycloak (login provisioning client, SMTP to Mailpit, MFA flow)"
docker compose up -d keycloak >/dev/null
for _ in $(seq 1 60); do
  docker compose exec -T keycloak bash -c 'exec 3<>/dev/tcp/localhost/8080' 2>/dev/null && break
  sleep 2
done
./scripts/keycloak-login-provisioning-dev.sh
./scripts/keycloak-stepup-dev.sh >/dev/null && echo "MFA step-up flow in place"

echo "== Rebuild and restart"
docker compose up -d --build iam-core iam-worker iam-web
docker compose restart iam-proxy >/dev/null
if [ -n "$(docker compose --profile lab ps -q lab-postgres 2>/dev/null)" ]; then
  # lab databases created before the PostgreSQL 16 ADMIN grant was added to the init script
  docker compose --profile lab exec -T lab-postgres sh -c 'psql -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "GRANT readers, alice, bob, app_writer, old_contractor TO iam_service WITH ADMIN TRUE, INHERIT FALSE, SET FALSE"' >/dev/null 2>&1 \
    && echo "  lab-postgres: iam_service holds ADMIN on the lab roles" || true
fi

echo "== Waiting for health"
for svc in iam-core iam-worker iam-web; do
  for _ in $(seq 1 60); do
    state="$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q "$svc")" 2>/dev/null || echo unknown)"
    [ "$state" = healthy ] && break
    sleep 2
  done
  echo "  $svc: $state"
done
sleep 2
code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/oauth2/authorization/keycloak)"
echo "  login redirect: HTTP $code (expected 302)"
docker compose logs iam-core 2>/dev/null | grep -o "Login provisioning through the Keycloak admin API is [a-z]*" | tail -1 || true
URL="$(grep -E '^IAM_PUBLIC_URL=' .env 2>/dev/null | cut -d= -f2- || true)"
echo "Done. Open the platform: ${URL:-http://SERVER:8088}/"
