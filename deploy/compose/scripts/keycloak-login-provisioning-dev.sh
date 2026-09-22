#!/usr/bin/env bash
# DEVELOPMENT: lets iam-core create login accounts in Keycloak ("Create login" on the Users page).
#
# Idempotent, run from deploy/compose after ./scripts/generate-dev-secrets.sh:
#   1. creates the confidential client iam-core-admin (client credentials only) with the secret from
#      secrets/keycloak_admin_client_secret, or re-syncs its secret,
#   2. gives its service account only realm-management manage-users, view-users and query-users,
#   3. points the realm's SMTP at Mailpit so invitation e-mails (set password + enrol MFA) are delivered.
# Afterwards: docker compose up -d iam-core   (it reads the new secret)
set -euo pipefail
cd "$(dirname "$0")/.."

REALM="${REALM:-iam}"
CLIENT="iam-core-admin"
SECRET_FILE="secrets/keycloak_admin_client_secret"
[ -s "$SECRET_FILE" ] || { echo "missing $SECRET_FILE - run ./scripts/generate-dev-secrets.sh first" >&2; exit 1; }
SECRET="$(cat "$SECRET_FILE")"

kc() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config </dev/null; }

docker compose exec -T keycloak bash -c \
  '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config --server http://localhost:8080/auth \
     --realm master --user kcadmin --password "$(cat /run/secrets/keycloak_admin_password)"' >/dev/null
echo "Logged in to Keycloak admin API"

ID="$(kc get clients -r "$REALM" -q clientId="$CLIENT" --fields id --format csv --noquotes | head -1)"
if [ -z "$ID" ]; then
  kc create clients -r "$REALM" -s clientId="$CLIENT" -s name="IAM Core - login provisioning" -s enabled=true \
    -s publicClient=false -s clientAuthenticatorType=client-secret -s secret="$SECRET" -s serviceAccountsEnabled=true \
    -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s implicitFlowEnabled=false >/dev/null
  echo "Created client $CLIENT"
else
  kc update "clients/$ID" -r "$REALM" -s secret="$SECRET" -s serviceAccountsEnabled=true >/dev/null
  echo "Client $CLIENT exists - secret synchronised"
fi

kc add-roles -r "$REALM" --uusername "service-account-$CLIENT" --cclientid realm-management \
  --rolename manage-users --rolename view-users --rolename query-users
echo "Service account roles: manage-users, view-users, query-users"

kc update "realms/$REALM" -s smtpServer.host=mailpit -s smtpServer.port=1025 -s smtpServer.from=iam-noreply@iam.local \
  -s 'smtpServer.fromDisplayName=Enterprise IAM' -s smtpServer.auth=false -s smtpServer.ssl=false -s smtpServer.starttls=false
echo "Realm SMTP -> mailpit:1025 (invitations appear in Mailpit)"
echo "Done. Now: docker compose up -d iam-core"
