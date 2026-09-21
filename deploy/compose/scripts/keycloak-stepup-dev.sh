#!/usr/bin/env bash
# DEVELOPMENT: make MFA visible to iam-core so step-up works (SEC5, ADR-0016).
#
# Keycloak's `amr` mapper only emits a method when the authenticator execution carries an
# "Authentication Reference" config. The built-in browser flow has none, so tokens carried no
# `amr` and step-up protected actions always answered STEP_UP_REQUIRED.
#
# This script (idempotent, run from deploy/compose):
#   1. copies the built-in `browser` flow to `iam-browser` (built-in flows are read-only),
#   2. tags the password form with amr=pwd and the OTP form with amr=otp,
#   3. binds `iam-browser` as the realm's browser flow.
# Afterwards sign out and sign in again (…/oauth2/authorization/keycloak?stepup=1).
set -euo pipefail
cd "$(dirname "$0")/.."

REALM="${REALM:-iam}"
FLOW="iam-browser"

kc() { # run kcadm inside the keycloak container
  # </dev/null: `docker compose exec` would otherwise swallow the stdin of the surrounding while-read loop
  docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config </dev/null
}

docker compose exec -T keycloak bash -c \
  '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config --server http://localhost:8080/auth \
     --realm master --user kcadmin --password "$(cat /run/secrets/keycloak_admin_password)"' >/dev/null
echo "Logged in to Keycloak admin API"

if kc get authentication/flows -r "$REALM" --fields alias | grep -q "\"$FLOW\""; then
  echo "Flow $FLOW already exists"
else
  kc create authentication/flows/browser/copy -r "$REALM" -s "newName=$FLOW" >/dev/null
  echo "Copied browser flow to $FLOW"
fi

kc get "authentication/flows/$FLOW/executions" -r "$REALM" > /tmp/iam-flow-executions.json
python3 - "$REALM" <<'PY' > /tmp/iam-flow-plan.txt
import json, sys
wanted = {"auth-username-password-form": "pwd", "auth-otp-form": "otp", "webauthn-authenticator": "hwk"}
for e in json.load(open("/tmp/iam-flow-executions.json")):
    ref = wanted.get(e.get("providerId"))
    if ref and not e.get("authenticationConfig"):
        print(e["id"], ref)
    elif ref:
        print("#", e["providerId"], "already configured")
PY

found=0
while read -r id ref; do
  if [ "$id" = "#" ]; then echo "  $ref"; found=1; continue; fi
  kc create "authentication/executions/$id/config" -r "$REALM" \
    -b "{\"alias\":\"iam-amr-$ref\",\"config\":{\"default.reference.value\":\"$ref\",\"default.reference.maxAge\":\"28800\"}}" >/dev/null
  echo "  tagged execution $id with amr=$ref"; found=1
done < /tmp/iam-flow-plan.txt
[ "$found" = 1 ] || { echo "No password/OTP executions found in $FLOW - check the flow in the admin console" >&2; exit 1; }

kc update "realms/$REALM" -s "browserFlow=$FLOW"
echo "Realm '$REALM' now uses browser flow '$FLOW'."
echo "Sign out, then sign in via  \$IAM_PUBLIC_URL/oauth2/authorization/keycloak?stepup=1  (password + TOTP)."
rm -f /tmp/iam-flow-executions.json /tmp/iam-flow-plan.txt
