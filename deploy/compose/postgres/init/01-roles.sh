#!/bin/sh
# Creates the platform database roles (DATA-MODEL.md §1.10). Runs once, on first initialisation of pg-data.
# Passwords come from Compose secrets; nothing is hard-coded.
set -eu
owner_pw="$(cat /run/secrets/iam_owner_password)"
app_pw="$(cat /run/secrets/iam_app_password)"
ro_pw="$(cat /run/secrets/iam_readonly_password)"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_pw="$owner_pw" -v app_pw="$app_pw" -v ro_pw="$ro_pw" <<'SQL'
CREATE ROLE iam_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE iam_app LOGIN PASSWORD :'app_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE iam_readonly LOGIN PASSWORD :'ro_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT CONNECT, CREATE ON DATABASE iam TO iam_owner;
GRANT CONNECT ON DATABASE iam TO iam_app, iam_readonly;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
