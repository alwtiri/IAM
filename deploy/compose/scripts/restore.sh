#!/usr/bin/env bash
# Restores a backup made by backup.sh onto this installation (Phase 11 runbook). DESTRUCTIVE: replaces the IAM and
# Keycloak databases and the Vault data. Usage: ./scripts/restore.sh /var/backups/iam/<timestamp> --yes
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="${1:?backup directory}"
[ "${2:-}" = "--yes" ] || { echo "This replaces all platform data with $SRC. Re-run with --yes to continue." >&2; exit 2; }
( cd "$SRC" && sha256sum -c SHA256SUMS ) || { echo "Checksum mismatch - backup is damaged" >&2; exit 1; }
json() { python3 -c "import json,sys;d=json.load(open('secrets/vault-dev-init.json'));print(eval(sys.argv[1]))" "$1"; }

echo "== Stopping application services"
docker compose stop iam-core iam-worker keycloak >/dev/null
echo "== Secrets"
tar -xzf "$SRC/secrets.tgz"
echo "== IAM database"
docker compose exec -T postgres pg_restore -U iam_superuser -d iam --clean --if-exists --no-owner --role=iam_owner < "$SRC/iam.dump" || true
echo "== Keycloak database"
docker compose exec -T keycloak-db pg_restore -U keycloak -d keycloak --clean --if-exists --no-owner < "$SRC/keycloak.dump" || true
if [ -s "$SRC/vault.snap" ]; then
  echo "== Vault"
  docker compose exec -T -e VAULT_TOKEN="$(json "d['root_token']")" vault sh -c 'cat > /tmp/vault.snap && vault operator raft snapshot restore -force /tmp/vault.snap && rm -f /tmp/vault.snap' < "$SRC/vault.snap"
fi
echo "== Starting services"
docker compose up -d keycloak iam-core iam-worker >/dev/null
echo "Restore finished. Check: Administration > System Health, and log in."
