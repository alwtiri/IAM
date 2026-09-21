-- =====================================================================
-- V1 — Phase 1 migration foundation (DATA-MODEL.md §1, §3)
-- Creates one schema per Core module. Tables are added by later, forward-only
-- migrations in the phase that implements each module. No data is created or removed.
-- Runs as the owner role (iam_owner). Never edit this file after it has been applied.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS organization;
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS "authorization";
CREATE SCHEMA IF NOT EXISTS policy;
CREATE SCHEMA IF NOT EXISTS risk;
CREATE SCHEMA IF NOT EXISTS sod;
CREATE SCHEMA IF NOT EXISTS target;
CREATE SCHEMA IF NOT EXISTS provider;
CREATE SCHEMA IF NOT EXISTS account;
CREATE SCHEMA IF NOT EXISTS secrets;
CREATE SCHEMA IF NOT EXISTS request;
CREATE SCHEMA IF NOT EXISTS approval;
CREATE SCHEMA IF NOT EXISTS session;
CREATE SCHEMA IF NOT EXISTS operation;
CREATE SCHEMA IF NOT EXISTS notification;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS reporting;

COMMENT ON SCHEMA audit IS 'Append-only audit. Application role: INSERT/SELECT only (ADR-0008).';
COMMENT ON SCHEMA secrets IS 'Credential metadata and Vault references only. Secret values are never stored here (ADR-0005).';

-- Application role privileges (the roles themselves are created by the database bootstrap script,
-- deploy/compose/postgres/init/01-roles.sh, so that migration scripts contain no passwords).
DO $$
DECLARE s text;
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iam_app') THEN
    FOREACH s IN ARRAY ARRAY['organization','identity','authorization','policy','risk','sod','target','provider',
                             'account','secrets','request','approval','session','operation','notification','reporting']
    LOOP
      EXECUTE format('GRANT USAGE ON SCHEMA %I TO iam_app', s);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO iam_app', s);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO iam_app', s);
    END LOOP;
    -- Audit: insert and read only. No UPDATE/DELETE, ever.
    EXECUTE 'GRANT USAGE ON SCHEMA audit TO iam_app';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT, INSERT ON TABLES TO iam_app';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT USAGE, SELECT ON SEQUENCES TO iam_app';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iam_readonly') THEN
    FOREACH s IN ARRAY ARRAY['reporting','audit']
    LOOP
      EXECUTE format('GRANT USAGE ON SCHEMA %I TO iam_readonly', s);
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT ON TABLES TO iam_readonly', s);
    END LOOP;
  END IF;
END $$;
