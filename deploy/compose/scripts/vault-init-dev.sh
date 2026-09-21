#!/usr/bin/env bash
# DEVELOPMENT ONLY: initialise + unseal the dev Vault (single key share) and configure what iam-core needs:
#   - KV v2 secrets engine at 'iam/'
#   - AppRole auth with role 'iam-core' limited by policy 'iam-core' to iam/*/providers/*
#   - role_id / secret_id written to ./secrets/vault_role_id and ./secrets/vault_secret_id (read by iam-core at login)
# Production uses Shamir shares held by separate custodians or auto-unseal, and response-wrapped secret ids (Phase 10).
set -euo pipefail
cd "$(dirname "$0")/.."
out=secrets/vault-dev-init.json
vault_exec() { docker compose exec -T -e VAULT_TOKEN="${ROOT_TOKEN:-}" vault vault "$@"; }

if docker compose exec -T vault vault status -format=json 2>/dev/null | grep -q '"initialized": true'; then
  echo "Vault already initialised"
else
  docker compose exec -T vault vault operator init -key-shares=1 -key-threshold=1 -format=json > "$out"
  chmod 600 "$out"
  echo "Vault initialised; dev unseal key and root token stored in $out (git-ignored)"
fi
[[ -s "$out" ]] || { echo "missing $out — cannot unseal"; exit 1; }
json() { python3 -c "import json,sys;d=json.load(open('$out'));print($1)"; }
docker compose exec -T vault vault operator unseal "$(json "d['unseal_keys_b64'][0]")" >/dev/null
echo "Vault unsealed"
ROOT_TOKEN="$(json "d['root_token']")"

vault_exec secrets list -format=json | grep -q '"iam/"' || vault_exec secrets enable -path=iam kv-v2
vault_exec auth list -format=json | grep -q '"approle/"' || vault_exec auth enable approle
docker compose exec -T -e VAULT_TOKEN="$ROOT_TOKEN" vault sh -c 'cat > /tmp/iam-core.hcl <<POLICY
path "iam/data/providers/*"     { capabilities = ["create", "update", "read"] }
path "iam/metadata/providers/*" { capabilities = ["delete", "read"] }
POLICY
vault policy write iam-core /tmp/iam-core.hcl && rm -f /tmp/iam-core.hcl'
vault_exec write auth/approle/role/iam-core token_policies=iam-core token_ttl=1h token_max_ttl=4h secret_id_ttl=0 >/dev/null
printf '%s' "$(vault_exec read -field=role_id auth/approle/role/iam-core/role-id)" > secrets/vault_role_id
printf '%s' "$(vault_exec write -f -field=secret_id auth/approle/role/iam-core/secret-id)" > secrets/vault_secret_id
chmod 644 secrets/vault_role_id secrets/vault_secret_id
echo "Vault configured for iam-core (KV 'iam/', AppRole 'iam-core'); credentials written to secrets/vault_role_id and vault_secret_id"
