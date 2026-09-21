-- V6 — Authorization: permission catalog, roles, scoped role assignments (spec §19, PHASE-2-DESIGN §3.3)

CREATE TABLE "authorization".permission (
    code        text PRIMARY KEY CHECK (code ~ '^[a-z][a-z-]*(:[a-z][a-z-]*)+$'),
    description text NOT NULL
);

CREATE TABLE "authorization".role (
    id          uuid    PRIMARY KEY,
    code        text    NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    name        text    NOT NULL,
    description text,
    built_in    boolean NOT NULL DEFAULT false
);

CREATE TABLE "authorization".role_permission (
    role_id         uuid NOT NULL REFERENCES "authorization".role (id),
    permission_code text NOT NULL REFERENCES "authorization".permission (code),
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE "authorization".role_assignment (
    id          uuid        PRIMARY KEY,
    identity_id uuid        NOT NULL REFERENCES identity.identity (id),
    role_id     uuid        NOT NULL REFERENCES "authorization".role (id),
    source      text        NOT NULL CHECK (source IN ('DIRECT', 'BOOTSTRAP', 'REQUEST', 'JML', 'EMERGENCY')),
    status      text        NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    scope_key   text        NOT NULL,
    valid_from  timestamptz NOT NULL,
    valid_until timestamptz,
    granted_by  uuid,
    granted_at  timestamptz NOT NULL,
    revoked_by  uuid,
    revoked_at  timestamptz,
    reason      text,
    version     bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_role_assignment_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_role_assignment_no_self_grant CHECK (granted_by IS NULL OR granted_by <> identity_id)
);
CREATE INDEX ix_role_assignment_identity ON "authorization".role_assignment (identity_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_role_assignment_expiry ON "authorization".role_assignment (valid_until) WHERE status = 'ACTIVE';
-- scope_key is the canonical, sorted scope description; identical active grants are rejected
CREATE UNIQUE INDEX uq_role_assignment_active ON "authorization".role_assignment (identity_id, role_id, scope_key) WHERE status = 'ACTIVE';

CREATE TABLE "authorization".role_assignment_scope (
    assignment_id uuid NOT NULL REFERENCES "authorization".role_assignment (id),
    element_type  text NOT NULL CHECK (element_type IN ('GLOBAL', 'ORG_UNIT', 'ORG_UNIT_TREE', 'ENVIRONMENT', 'PROVIDER_TYPE', 'PROVIDER_INSTANCE', 'TARGET')),
    element_value text NOT NULL,
    PRIMARY KEY (assignment_id, element_type, element_value)
);

-- Permission catalog (must equal Permissions.ALL — PermissionCatalogTest)
INSERT INTO "authorization".permission (code, description) VALUES
    ('system:read', 'Read platform information and catalogs'),
    ('system:health:read', 'Read component health'),
    ('org:read', 'Read organization structure'),
    ('org:write', 'Create and change organization structure'),
    ('person:read', 'Read persons'),
    ('person:write', 'Create and change persons'),
    ('identity:read', 'Read identities'),
    ('identity:write', 'Create identities'),
    ('identity:lifecycle', 'Change identity lifecycle state'),
    ('identity:platform-user', 'Link identities to platform logins'),
    ('role:read', 'Read roles and permissions'),
    ('role-assignment:read', 'Read role assignments'),
    ('role-assignment:write', 'Grant and revoke role assignments'),
    ('audit:read', 'Read audit events'),
    ('audit:verify', 'Verify audit chain integrity'),
    ('operation:read', 'Read operations'),
    ('target:read', 'Read targets'),
    ('target:write', 'Create and change targets'),
    ('provider:read', 'Read provider instances'),
    ('provider:write', 'Register and change provider instances (credentials go to Vault)');

-- Built-in roles (spec §19). Deterministic ids so later migrations can reference them.
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000101', 'PLATFORM_ADMINISTRATOR', 'Platform Administrator', 'Full platform administration', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000101', code FROM "authorization".permission WHERE code IN ('system:read', 'system:health:read', 'org:read', 'org:write', 'person:read', 'person:write', 'identity:read', 'identity:write', 'identity:lifecycle', 'identity:platform-user', 'role:read', 'role-assignment:read', 'role-assignment:write', 'audit:read', 'audit:verify', 'operation:read', 'target:read', 'target:write', 'provider:read', 'provider:write');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000102', 'IAM_ADMINISTRATOR', 'IAM Administrator', 'Organization, persons, identities and role assignments', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000102', code FROM "authorization".permission WHERE code IN ('system:read', 'org:read', 'org:write', 'person:read', 'person:write', 'identity:read', 'identity:write', 'identity:lifecycle', 'identity:platform-user', 'role:read', 'role-assignment:read', 'role-assignment:write', 'operation:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000103', 'SECURITY_ADMINISTRATOR', 'Security Administrator', 'Security oversight: audit, roles, health', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000103', code FROM "authorization".permission WHERE code IN ('system:read', 'system:health:read', 'role:read', 'role-assignment:read', 'audit:read', 'audit:verify', 'identity:read', 'person:read', 'org:read', 'operation:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000104', 'INFRASTRUCTURE_ADMINISTRATOR', 'Infrastructure Administrator', 'Targets and provider instances', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000104', code FROM "authorization".permission WHERE code IN ('system:read', 'system:health:read', 'target:read', 'target:write', 'provider:read', 'provider:write', 'operation:read', 'org:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000105', 'PAM_ADMINISTRATOR', 'PAM Administrator', 'Privileged access administration (extended in Phase 6)', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000105', code FROM "authorization".permission WHERE code IN ('system:read', 'target:read', 'provider:read', 'operation:read', 'identity:read', 'role:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000106', 'AUDITOR', 'Auditor', 'Read everything and verify audit integrity', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000106', code FROM "authorization".permission WHERE code IN ('system:read', 'system:health:read', 'org:read', 'person:read', 'identity:read', 'role:read', 'role-assignment:read', 'audit:read', 'operation:read', 'target:read', 'provider:read', 'audit:verify');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000107', 'HELPDESK', 'Helpdesk', 'Look up persons and identities', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000107', code FROM "authorization".permission WHERE code IN ('person:read', 'identity:read', 'org:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000108', 'APPLICATION_ADMINISTRATOR', 'Application Administrator', 'Application targets (extended in later phases)', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000108', code FROM "authorization".permission WHERE code IN ('target:read', 'operation:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000109', 'DATABASE_ADMINISTRATOR', 'Database Administrator', 'Database targets (extended in later phases)', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000109', code FROM "authorization".permission WHERE code IN ('target:read', 'operation:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000110', 'NETWORK_ADMINISTRATOR', 'Network Administrator', 'Network targets (extended in later phases)', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000110', code FROM "authorization".permission WHERE code IN ('target:read', 'operation:read');
INSERT INTO "authorization".role (id, code, name, description, built_in) VALUES ('00000000-0000-7000-8000-000000000111', 'READ_ONLY_AUDITOR', 'Read Only Auditor', 'Read-only access to all data, no verification', true);
INSERT INTO "authorization".role_permission (role_id, permission_code) SELECT '00000000-0000-7000-8000-000000000111', code FROM "authorization".permission WHERE code IN ('system:read', 'system:health:read', 'org:read', 'person:read', 'identity:read', 'role:read', 'role-assignment:read', 'audit:read', 'operation:read', 'target:read', 'provider:read');

-- Built-in roles and their permissions are immutable; custom roles are introduced in a later phase.
CREATE FUNCTION "authorization".protect_builtin_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') AND OLD.built_in THEN
        RAISE EXCEPTION 'built-in role % cannot be modified', OLD.code USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER trg_role_builtin BEFORE UPDATE OR DELETE ON "authorization".role
    FOR EACH ROW EXECUTE FUNCTION "authorization".protect_builtin_role();
