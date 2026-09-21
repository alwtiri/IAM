#!/usr/bin/env bash
# DEVELOPMENT ONLY: initialises and unseals the dev Vault with a single key share.
# Production uses Shamir shares held by separate custodians or auto-unseal (Phase 10 DR guide).
set -euo pipefail
cd "$(dirname "$0")/.."
out=secrets/vault-dev-init.json
if docker compose exec -T vault vault status -format=json 2>/dev/null | grep -q '"initialized": true'; then
  echo "Vault already initialised"
else
  docker compose exec -T vault vault operator init -key-shares=1 -key-threshold=1 -format=json > "$out"
  chmod 600 "$out"
  echo "Vault initialised; dev unseal key and root token stored in $out (git-ignored)"
fi
key=$(python3 -c "import json;print(json.load(open('$out'))['unseal_keys_b64'][0])")
docker compose exec -T vault vault operator unseal "$key" >/dev/null
echo "Vault unsealed"
