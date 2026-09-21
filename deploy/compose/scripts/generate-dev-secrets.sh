#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets
chmod 700 secrets
random_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
  elif command -v python3 >/dev/null 2>&1; then python3 -c 'import secrets; print(secrets.token_hex(16))'
  else od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; fi
}
for name in pg_superuser_password iam_owner_password iam_app_password iam_readonly_password \
            keycloak_db_password keycloak_admin_password keycloak_client_secret rabbitmq_password cache_password; do
  [[ -s "secrets/$name" ]] || { printf '%s' "$(random_secret)" > "secrets/$name"; echo "created secrets/$name"; }
  chmod 644 "secrets/$name"
done
