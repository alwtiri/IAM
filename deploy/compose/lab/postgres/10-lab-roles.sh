#!/bin/sh
# Lab database roles for trying the postgresql provider (compose profile "lab"; never deployed).
set -eu
SVC_PW="$(cat /run/secrets/lab_pg_service_password)"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v svc_pw="$SVC_PW" <<'SQL'
CREATE ROLE iam_service LOGIN CREATEROLE PASSWORD :'svc_pw';
CREATE ROLE readers NOLOGIN;
CREATE ROLE alice LOGIN IN ROLE readers;
CREATE ROLE bob LOGIN;
CREATE ROLE app_writer LOGIN IN ROLE pg_write_all_data;
CREATE ROLE old_contractor NOLOGIN;
SQL
