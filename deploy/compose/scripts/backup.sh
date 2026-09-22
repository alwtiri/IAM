#!/usr/bin/env bash
# Consistent backup of everything needed to rebuild the platform (Phase 11 runbook):
#   IAM database (pg_dump custom format), Keycloak database, Vault Raft snapshot, and the secrets folder.
# Usage: ./scripts/backup.sh [target-dir]        (default /var/backups/iam; keeps the last 14 backups)
# The result contains secret material (Vault snapshot + secrets/). Store it encrypted and off the host.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="${1:-/var/backups/iam}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$BASE/$TS"
umask 077
mkdir -p "$OUT"
json() { python3 -c "import json,sys;d=json.load(open('secrets/vault-dev-init.json'));print(eval(sys.argv[1]))" "$1"; }

echo "== IAM database"
docker compose exec -T postgres pg_dump -U iam_superuser -d iam -Fc > "$OUT/iam.dump"
echo "== Keycloak database"
docker compose exec -T keycloak-db pg_dump -U keycloak -d keycloak -Fc > "$OUT/keycloak.dump"
echo "== Vault (Raft snapshot)"
if [ -s secrets/vault-dev-init.json ]; then
  docker compose exec -T -e VAULT_TOKEN="$(json "d['root_token']")" vault sh -c 'vault operator raft snapshot save /tmp/vault.snap >/dev/null && cat /tmp/vault.snap && rm -f /tmp/vault.snap' > "$OUT/vault.snap"
else
  echo "   secrets/vault-dev-init.json missing - set VAULT_TOKEN and run: vault operator raft snapshot save" >&2
fi
echo "== Secrets and configuration"
tar -czf "$OUT/secrets.tgz" secrets .env 2>/dev/null || tar -czf "$OUT/secrets.tgz" secrets
git -C .. rev-parse HEAD > "$OUT/git-commit.txt" 2>/dev/null || true

( cd "$OUT" && sha256sum ./* > SHA256SUMS )
# retention: keep the 14 newest backup folders created by this script (names YYYYMMDD-HHMMSS only)
find "$BASE" -mindepth 1 -maxdepth 1 -type d -name '20[0-9][0-9][01][0-9][0-3][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]' | sort -r | tail -n +15 | xargs -r rm -rf --
echo "Backup written to $OUT"
du -sh "$OUT" | cut -f1
