#!/usr/bin/env bash
# Phase 2 gate smoke test (condition C2). Run from deploy/compose on the server.
#
# Step-up protected calls (role grant, provider registration, audit verification) need an MFA login
# less than 5 minutes old, so:
#   1. In the browser open  $IAM_PUBLIC_URL/oauth2/authorization/keycloak?stepup=1  and log in with password + TOTP.
#   2. DevTools -> Application -> Cookies: copy the values of IAM_SESSION and XSRF-TOKEN.
#   3. Immediately run:
#        IAM_SESSION=... XSRF=... ./scripts/smoke-phase2.sh            # functional checks
#        IAM_SESSION=... XSRF=... ./scripts/smoke-phase2.sh --vault-down  # + Vault outage check (stops/unseals Vault)
set -uo pipefail

BASE="${BASE:-$(grep -E '^IAM_PUBLIC_URL=' .env 2>/dev/null | cut -d= -f2-)}"
BASE="${BASE:-http://127.0.0.1:8088}"
MAILPIT="${MAILPIT:-http://127.0.0.1:${IAM_MAILPIT_PORT:-8025}}"
: "${IAM_SESSION:?set IAM_SESSION (browser cookie)}"
: "${XSRF:?set XSRF (XSRF-TOKEN browser cookie)}"
VAULT_DOWN=0; [ "${1:-}" = "--vault-down" ] && VAULT_DOWN=1

TS="$(date +%s)"; PASS=0; FAIL=0
BODY="$(mktemp)"; trap 'rm -f "$BODY"' EXIT

ok()   { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
jget() { python3 -c "import json,sys;d=json.load(open('$BODY'));print(eval(sys.argv[1]))" "$1" 2>/dev/null; }

# call METHOD PATH [JSON] -> sets $CODE, body in $BODY
call() {
  local args=(-s -o "$BODY" -w '%{http_code}' -X "$1" "$BASE$2"
    -H "Cookie: IAM_SESSION=$IAM_SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF"
    -H "Accept: application/json" -H "X-Correlation-Id: smoke-$TS-$RANDOM")
  [ -n "${3:-}" ] && args+=(-H "Content-Type: application/json" -d "$3")
  CODE="$(curl "${args[@]}")"
}
expect() { # expect CODE-REGEX description
  if [[ "$CODE" =~ ^($1)$ ]]; then ok "$2 ($CODE)"; else bad "$2 (got $CODE: $(head -c 300 "$BODY"))"; fi
}

echo "Target: $BASE"
echo "1. Session"
call GET /api/v1/me; expect 200 "GET /me"
[ "$CODE" = 200 ] || { echo "Session cookie invalid or expired - log in again and copy fresh cookies."; exit 1; }
echo "     actor: $(jget "d.get('displayName') or d.get('username') or d")"
AGE="$(jget "int(__import__('time').time() - __import__('calendar').timegm(__import__('time').strptime(d['authenticatedAt'][:19], '%Y-%m-%dT%H:%M:%S')))")"
echo "     acr: $(jget "d.get('authenticationContext')")   authenticated ${AGE:-?}s ago"
if [ -n "$AGE" ] && [ "$AGE" -gt 300 ]; then
  echo "     WARNING: login is older than 5 minutes - step-up checks will fail. Sign in again with ?stepup=1."
fi

echo "2. Organization / person / identity"
call POST /api/v1/org-units "{\"kind\":\"DEPARTMENT\",\"code\":\"SMOKE-$TS\",\"name\":\"Smoke $TS\"}"
expect 201 "create org unit"; OU="$(jget "d['id']")"

call POST /api/v1/persons "{\"givenName\":\"Smoke\",\"familyName\":\"Test $TS\",\"email\":\"smoke.$TS@example.org\",\"orgUnitId\":\"$OU\",\"employmentStatus\":\"ACTIVE\"}"
expect 201 "create person"; PERSON="$(jget "d['id']")"

call POST /api/v1/identities "{\"personId\":\"$PERSON\",\"type\":\"EMPLOYEE\",\"username\":\"smoke.$TS\"}"
expect 201 "create identity"; IDENT="$(jget "d['id']")"

call POST "/api/v1/identities/$IDENT:activate" '{"reason":"smoke test"}'
expect "200|204" "activate identity (lifecycle e-mail queued)"

echo "3. Scoped role grant (step-up)"
call POST /api/v1/role-assignments "{\"identityId\":\"$IDENT\",\"roleId\":\"00000000-0000-7000-8000-000000000107\",\"scope\":[{\"type\":\"ORG_UNIT\",\"value\":\"$OU\"}],\"reason\":\"smoke test\"}"
if grep -q STEP_UP_REQUIRED "$BODY"; then
  bad "grant HELPDESK scoped to org unit: STEP_UP_REQUIRED - login older than 5 min, or MFA not reported by Keycloak (run ./scripts/keycloak-stepup-dev.sh once)"
else
  expect 201 "grant HELPDESK scoped to ORG_UNIT"; GRANT="$(jget "d['id']")"
  call POST /api/v1/role-assignments "{\"identityId\":\"$IDENT\",\"roleId\":\"00000000-0000-7000-8000-000000000107\",\"scope\":[{\"type\":\"ORG_UNIT\",\"value\":\"$OU\"}]}"
  expect "409" "duplicate active grant rejected"
  call POST "/api/v1/role-assignments/$GRANT:revoke" '{"reason":"smoke test cleanup"}'
  expect "200|204" "revoke grant"
fi

echo "4. Negative checks"
call GET /api/v1/persons/00000000-0000-7000-8000-00000000ffff; expect 404 "unknown person -> 404"
call POST /api/v1/org-units '{"kind":"TEAM","code":"bad code","name":"x"}'; expect 400 "invalid input -> 400"
CODE="$(curl -s -o "$BODY" -w '%{http_code}' -X POST "$BASE/api/v1/org-units" -H "Cookie: IAM_SESSION=$IAM_SESSION" -H 'Content-Type: application/json' -d '{"kind":"TEAM","code":"NOCSRF","name":"x"}')"
expect 403 "mutation without CSRF token -> 403"
CODE="$(curl -s -o "$BODY" -w '%{http_code}' "$BASE/api/v1/me")"; expect 401 "no session -> 401"
CODE="$(curl -s -o "$BODY" -w '%{http_code}' "$BASE/actuator/env")"; expect 404 "actuator not exposed via proxy"

echo "5. Provider registration -> credential stored in Vault only"
SECRET="smoke-secret-$TS-$RANDOM"
call POST /api/v1/provider-instances "{\"type\":\"linux-ssh\",\"name\":\"smoke-$TS\",\"endpoint\":\"ssh://smoke.example.org:22\",\"credential\":\"$SECRET\",\"settings\":{\"note\":\"smoke\"}}"
expect 201 "register provider instance"; PROV="$(jget "d['id']")"
if grep -q "$SECRET" "$BODY"; then bad "credential echoed in response"; else ok "credential not in response"; fi
call GET "/api/v1/provider-instances/$PROV"
if grep -q "$SECRET" "$BODY"; then bad "credential returned by GET"; else ok "credential not returned by GET"; fi
if docker compose logs --since 10m iam-core 2>/dev/null | grep -q "$SECRET"; then bad "credential found in iam-core logs"; else ok "credential not in iam-core logs"; fi
call POST /api/v1/provider-instances '{"type":"linux-ssh","name":"smoke-bad-'"$TS"'","endpoint":"ssh://x:22","settings":{"password":"x"}}'
expect 400 "secret-looking setting key rejected"

echo "6. Audit"
call GET "/api/v1/audit-events?limit=20"; expect 200 "list audit events"
call GET /api/v1/audit/verification; expect 200 "verify audit chain"
[ "$(jget "d['valid']")" = "True" ] && ok "audit chain valid ($(jget "d['eventsChecked']") events)" || bad "audit chain invalid: $(head -c 300 "$BODY")"
AUDIT_SQL="begin; update audit.audit_event set action=action where id=(select id from audit.audit_event limit 1); rollback;"
if docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U iam_superuser -d iam -c "$AUDIT_SQL" >/dev/null 2>&1; then
  bad "audit UPDATE was not blocked (even for superuser the trigger must reject it)"
else ok "audit UPDATE blocked by trigger"; fi

echo "7. E-mail (Mailpit)"
sleep 5
if curl -s "$MAILPIT/api/v1/search?query=to:smoke.$TS@example.org" | grep -q '"messages_count":[1-9]'; then
  ok "notification e-mail delivered to Mailpit"
else bad "no e-mail for smoke.$TS@example.org in Mailpit ($MAILPIT)"; fi

if [ "$VAULT_DOWN" = 1 ]; then
  echo "8. Vault outage (G3)"
  docker compose stop vault >/dev/null 2>&1
  call GET /api/v1/org-units; expect 200 "non-secret API works while Vault is down"
  call POST /api/v1/provider-instances "{\"type\":\"linux-ssh\",\"name\":\"smoke-v-$TS\",\"endpoint\":\"ssh://x:22\",\"credential\":\"x\"}"
  if [ "$CODE" = 503 ] && grep -q SECRETS_UNAVAILABLE "$BODY"; then ok "secret operation -> 503 SECRETS_UNAVAILABLE"; else bad "expected 503 SECRETS_UNAVAILABLE, got $CODE $(head -c 200 "$BODY")"; fi
  call GET /api/v1/system/health
  grep -q '"vault"' "$BODY" && ok "health reports vault component ($(jget "[c['status'] for c in d['components'] if c['component']=='vault']"))" || bad "health missing vault"
  docker compose start vault >/dev/null 2>&1; sleep 3
  KEY="$(python3 -c "import json;print(json.load(open('secrets/vault-dev-init.json'))['unseal_keys_b64'][0])")"
  docker compose exec -T vault vault operator unseal "$KEY" >/dev/null && ok "Vault restarted and unsealed" || bad "Vault unseal failed - run ./scripts/vault-init-dev.sh"
fi

echo
echo "Result: $PASS passed, $FAIL failed   (test data prefix: SMOKE-$TS / smoke.$TS)"
[ "$FAIL" = 0 ]
