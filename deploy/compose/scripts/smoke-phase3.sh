#!/usr/bin/env bash
# Phase 3 smoke test: server management end to end against a lab Linux server (compose profile "lab").
#   register server -> SSH connection (key in Vault) -> test connection -> discover accounts -> disable/enable an
#   account with read-back verification -> wrong host key rejected -> operation survives a worker restart.
# Run from deploy/compose on the server. Provider registration needs step-up, so:
#   1. Open  $IAM_PUBLIC_URL/oauth2/authorization/keycloak?stepup=1  and log in with password + TOTP.
#   2. Copy the cookies IAM_SESSION and XSRF-TOKEN, then immediately run:
#        IAM_SESSION=... XSRF=... ./scripts/smoke-phase3.sh
#   Add --no-worker-restart to skip the durability check (it stops iam-worker for a few seconds).
if [ "${BASH_SOURCE[0]}" != "$0" ]; then
  echo "Run this script, do not source it:  IAM_SESSION='...' XSRF='...' ./scripts/smoke-phase3.sh" >&2
  return 1
fi
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

BASE="${BASE:-$(grep -E '^IAM_PUBLIC_URL=' .env 2>/dev/null | cut -d= -f2-)}"
BASE="${BASE:-http://127.0.0.1:8088}"
if [ -z "${IAM_SESSION:-}" ] || [ -z "${XSRF:-}" ]; then
  echo "Missing cookies. Log in at $BASE/oauth2/authorization/keycloak?stepup=1, copy IAM_SESSION and XSRF-TOKEN, then run:" >&2
  echo "  IAM_SESSION='...' XSRF='...' ./scripts/smoke-phase3.sh" >&2
  exit 2
fi
WORKER_RESTART=1; [ "${1:-}" = "--no-worker-restart" ] && WORKER_RESTART=0

TS="$(date +%s)"; PASS=0; FAIL=0
BODY="$(mktemp)"; trap 'rm -f "$BODY"' EXIT
ok()   { echo "  PASS  $*"; PASS=$((PASS+1)); }
bad()  { echo "  FAIL  $*"; FAIL=$((FAIL+1)); }
jget() { python3 -c "import json,sys;d=json.load(open('$BODY'));print(eval(sys.argv[1]))" "$1" 2>/dev/null; }
call() {
  local args=(-s -o "$BODY" -w '%{http_code}' -X "$1" "$BASE$2"
    -H "Cookie: IAM_SESSION=$IAM_SESSION; XSRF-TOKEN=$XSRF" -H "X-XSRF-TOKEN: $XSRF"
    -H "Accept: application/json" -H "X-Correlation-Id: smoke3-$TS-$RANDOM")
  [ -n "${3:-}" ] && args+=(-H "Content-Type: application/json" -d "$3")
  CODE="$(curl "${args[@]}")"
}
expect() { if [[ "$CODE" =~ ^($1)$ ]]; then ok "$2 ($CODE)"; else bad "$2 (got $CODE: $(head -c 300 "$BODY"))"; fi; }
# wait_op ID [SECONDS] -> sets $OPSTATUS (final status or last seen), body = operation
wait_op() {
  local id="$1" limit="${2:-90}" i=0
  OPSTATUS=""
  while [ "$i" -lt "$limit" ]; do
    call GET "/api/v1/operations/$id"
    OPSTATUS="$(jget "d['status']")"
    case "$OPSTATUS" in SUCCESS|FAILED|TIMEOUT|CANCELLED|PARTIAL|UNKNOWN) return 0 ;; esac
    sleep 2; i=$((i+2))
  done
}
opinfo() { jget "d['status'] + ' ' + str(d.get('errorCode') or '') + ' ' + str(d.get('errorMessage') or d.get('verificationSummary') or '')"; }
lab() { docker compose --profile lab exec -T lab-linux "$@"; }

echo "Target: $BASE"
echo "0. Lab Linux server"
if [ ! -f secrets/lab_ssh_key ]; then
  ssh-keygen -q -t rsa -b 3072 -m PEM -N '' -C "iam-lab-svc" -f secrets/lab_ssh_key && chmod 600 secrets/lab_ssh_key
  echo "     generated secrets/lab_ssh_key (git-ignored)"
fi
docker compose --profile lab up -d --build lab-linux >/dev/null 2>&1 || { echo "cannot start lab-linux (docker compose --profile lab up -d --build lab-linux)"; exit 1; }
for _ in $(seq 1 30); do lab test -f /etc/ssh/ssh_host_ed25519_key.pub 2>/dev/null && break; sleep 1; done
FP="$(lab ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256 2>/dev/null | awk '{print $2}')"
if [[ "$FP" == SHA256:* ]]; then ok "lab-linux running, host key $FP"; else bad "lab-linux host key not readable"; exit 1; fi
KEY_JSON="$(python3 -c 'import json,sys;print(json.dumps(open(sys.argv[1]).read()))' secrets/lab_ssh_key)"

echo "1. Session"
call GET /api/v1/me; expect 200 "GET /me"
[ "$CODE" = 200 ] || { echo "Session cookie invalid or expired - log in again and copy fresh cookies."; exit 1; }

echo "2. Register the server"
call POST /api/v1/org-units "{\"kind\":\"DEPARTMENT\",\"code\":\"SRV-$TS\",\"name\":\"Servers $TS\"}"
expect 201 "create owning org unit"; OU="$(jget "d['id']")"
call POST /api/v1/targets "{\"name\":\"lab-linux-$TS\",\"hostname\":\"lab-linux\",\"type\":\"LINUX_SERVER\",\"operatingSystem\":\"Ubuntu 24.04\",\"environment\":\"TEST\",\"criticality\":\"MEDIUM\",\"ownerOrgUnitId\":\"$OU\"}"
expect 201 "create target LINUX_SERVER"; TARGET="$(jget "d['id']")"

echo "3. SSH connection (private key goes to Vault, never stored in the database)"
call POST /api/v1/provider-instances "{\"type\":\"linux-ssh\",\"name\":\"lab-linux-ssh-$TS\",\"endpoint\":\"ssh://lab-linux:22\",\"settings\":{\"username\":\"svc-iam\",\"authType\":\"key\",\"hostKeyFingerprint\":\"$FP\"},\"credential\":$KEY_JSON}"
if grep -q STEP_UP_REQUIRED "$BODY"; then bad "register provider instance: STEP_UP_REQUIRED - log in again with ?stepup=1 and rerun within 5 minutes"; exit 1; fi
expect 201 "register linux-ssh provider instance"; PI="$(jget "d['id']")"
[ "$(jget "d['credentialConfigured']")" = "True" ] && ok "credential stored in Vault" || bad "credentialConfigured is not true"
grep -q "BEGIN RSA PRIVATE KEY" "$BODY" && bad "private key echoed in API response" || ok "private key not echoed"
call POST "/api/v1/targets/$TARGET/provider-bindings" "{\"providerInstanceId\":\"$PI\"}"
expect 200 "bind provider instance to target"

echo "4. Test connection"
call POST "/api/v1/targets/$TARGET:test-connection" "{\"providerInstanceId\":\"$PI\"}"
expect 202 "request connection test"; OP="$(jget "d['operationId']")"
wait_op "$OP" 90
[ "$OPSTATUS" = SUCCESS ] && ok "connection test SUCCESS (ssh + host key + sudo -n)" || bad "connection test: $(opinfo)"

echo "5. Discover accounts"
call POST "/api/v1/targets/$TARGET/discovery-runs" "{\"providerInstanceId\":\"$PI\"}"
expect 202 "start discovery"; OP="$(jget "d['operationId']")"
wait_op "$OP" 180
[ "$OPSTATUS" = SUCCESS ] && ok "discovery operation SUCCESS" || bad "discovery: $(opinfo)"
call GET "/api/v1/targets/$TARGET/discovery-runs"
[ "$(jget "d[0]['status']")" = COMPLETED ] && ok "discovery run COMPLETED, $(jget "d[0]['accountsSeen']") accounts seen" || bad "discovery run: $(head -c 300 "$BODY")"
call GET "/api/v1/accounts?targetId=$TARGET&limit=200"
expect 200 "list accounts of the server"
ALICE_PRIV="$(jget "[a['privileged'] for a in d['items'] if a['name']=='alice'][0]")"
BOB="$(jget "[a['id'] for a in d['items'] if a['name']=='bob'][0]")"
[ "$ALICE_PRIV" = True ] && ok "alice discovered as privileged (member of sudo)" || bad "alice not privileged"
[ -n "$BOB" ] && ok "bob discovered" || bad "bob not discovered"
[ "$(jget "[a['privileged'] for a in d['items'] if a['name']=='bob'][0]")" = False ] && ok "bob not privileged" || bad "bob privileged?"

echo "6. Disable and enable bob (verified by read-back)"
if [ -n "$BOB" ]; then
  call POST "/api/v1/accounts/$BOB:disable" '{"reason":"smoke test"}'
  expect 202 "request disable"; OP="$(jget "d['operationId']")"
  wait_op "$OP" 90
  [ "$OPSTATUS" = SUCCESS ] && ok "disable SUCCESS: $(jget "d.get('verificationSummary')")" || bad "disable: $(opinfo)"
  lab passwd -S bob | grep -qE '^bob (L|LK) ' && ok "on the server: bob is locked (passwd -S)" || bad "on the server bob is not locked: $(lab passwd -S bob)"
  call GET "/api/v1/accounts/$BOB"
  [ "$(jget "d['nativeStatus']")" = DISABLED ] && ok "platform shows bob DISABLED" || bad "platform status $(jget "d['nativeStatus']")"
  call POST "/api/v1/accounts/$BOB:enable" '{"reason":"smoke test"}'
  expect 202 "request enable"; OP="$(jget "d['operationId']")"
  wait_op "$OP" 90
  [ "$OPSTATUS" = SUCCESS ] && ok "enable SUCCESS" || bad "enable: $(opinfo)"
  lab passwd -S bob | grep -qE '^bob (P|PS) ' && ok "on the server: bob is usable again" || bad "on the server: $(lab passwd -S bob)"
fi

echo "7. Wrong host key is rejected (no trust on first use)"
call POST /api/v1/provider-instances "{\"type\":\"linux-ssh\",\"name\":\"lab-linux-badkey-$TS\",\"endpoint\":\"ssh://lab-linux:22\",\"settings\":{\"username\":\"svc-iam\",\"authType\":\"key\",\"hostKeyFingerprint\":\"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"},\"credential\":$KEY_JSON}"
expect 201 "register instance with a wrong fingerprint"; BADPI="$(jget "d['id']")"
call POST "/api/v1/targets/$TARGET/provider-bindings" "{\"providerInstanceId\":\"$BADPI\"}"
call POST "/api/v1/targets/$TARGET:test-connection" "{\"providerInstanceId\":\"$BADPI\"}"
OP="$(jget "d['operationId']")"; wait_op "$OP" 90
if [ "$OPSTATUS" = FAILED ] && [ "$(jget "d['errorCode']")" = AUTHENTICATION_FAILED ]; then ok "rejected: $(jget "d['errorMessage']")"; else bad "wrong host key: $(opinfo)"; fi
call DELETE "/api/v1/targets/$TARGET/provider-bindings/$BADPI"

if [ "$WORKER_RESTART" = 1 ]; then
  echo "8. Durability: operation queued while the worker is down completes after restart"
  docker compose stop iam-worker >/dev/null 2>&1
  call POST "/api/v1/targets/$TARGET:test-connection" "{\"providerInstanceId\":\"$PI\"}"
  expect 202 "request accepted with worker stopped"; OP="$(jget "d['operationId']")"
  sleep 5; call GET "/api/v1/operations/$OP"
  [[ "$(jget "d['status']")" =~ ^(QUEUED|RUNNING)$ ]] && ok "operation waits ($(jget "d['status']"))" || bad "unexpected status $(jget "d['status']")"
  docker compose start iam-worker >/dev/null 2>&1
  wait_op "$OP" 120
  [ "$OPSTATUS" = SUCCESS ] && ok "completed after worker restart" || bad "after restart: $(opinfo)"
fi

echo "9. Audit"
call GET "/api/v1/audit-events?limit=100"
for a in target.connection-test-requested account.discovery-requested account.operation-requested; do
  grep -q "\"$a\"" "$BODY" && ok "audit event $a" || bad "audit event $a missing"
done

echo
echo "Result: $PASS passed, $FAIL failed   (server: lab-linux-$TS, target $TARGET)"
[ "$FAIL" = 0 ]
