#!/usr/bin/env bash
# Generates random DEVELOPMENT secrets into deploy/compose/secrets/ (git-ignored). Never use for production.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p secrets
chmod 700 secrets

random_secret() {
  # 32 alphanumeric characters without pipelines that can die on SIGPIPE under 'set -o pipefail'.
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 16
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import secrets; print(secrets.token_hex(16))'
  else
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

for name in pg_superuser_password iam_owner_password iam_app_password iam_readonly_password \
            keycloak_db_password keycloak_admin_password keycloak_client_secret keycloak_admin_client_secret iam_admin_initial_password \
            rabbitmq_password cache_password; do
  if [[ ! -s "secrets/$name" ]]; then
    printf '%s' "$(random_secret)" > "secrets/$name"
    echo "created secrets/$name"
  else
    echo "kept    secrets/$name"
  fi
  # 0644: Compose bind-mounts file secrets with host permissions and non-root containers (uid 70, 999, 1000,
  # 10001) must read them. Host-side protection comes from the 0700 secrets/ directory.
  chmod 644 "secrets/$name"
done

# Vault AppRole credentials are written by vault-init-dev.sh; placeholders let Compose start before Vault is initialised
# (the Core then reports SECRETS_UNAVAILABLE for secret operations only — gate clarification G3).
for name in vault_role_id vault_secret_id; do
  [[ -s "secrets/$name" ]] || { printf 'uninitialized' > "secrets/$name"; echo "created secrets/$name (placeholder)"; }
  chmod 644 "secrets/$name"
done

# Internal mTLS PKI for the worker plane (Phase 3)
"$(dirname "$0")/pki-dev.sh"
