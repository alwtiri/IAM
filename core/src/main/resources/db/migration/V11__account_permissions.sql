-- V11 — Permissions for Account Management and operation execution (Phase 3).
-- Built-in roles stay immutable for the application; permissions are extended by migration only.

INSERT INTO "authorization".permission (code, description) VALUES
    ('account:read', 'Read accounts, entitlements and findings'),
    ('account:write', 'Change account governance (owner, link, type, exclusion)'),
    ('account:discover', 'Run account and group discovery on targets'),
    ('account:finding:resolve', 'Resolve account findings'),
    ('operation:execute', 'Run lifecycle operations on accounts through providers');

-- PLATFORM_ADMINISTRATOR: everything
INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000101', 'account:read'),
    ('00000000-0000-7000-8000-000000000101', 'account:write'),
    ('00000000-0000-7000-8000-000000000101', 'account:discover'),
    ('00000000-0000-7000-8000-000000000101', 'account:finding:resolve'),
    ('00000000-0000-7000-8000-000000000101', 'operation:execute');
-- IAM_ADMINISTRATOR: governance of accounts and lifecycle operations
INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000102', 'account:read'),
    ('00000000-0000-7000-8000-000000000102', 'account:write'),
    ('00000000-0000-7000-8000-000000000102', 'account:finding:resolve'),
    ('00000000-0000-7000-8000-000000000102', 'operation:execute');
-- SECURITY_ADMINISTRATOR: read accounts and findings
INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000103', 'account:read');
-- INFRASTRUCTURE_ADMINISTRATOR: discovery and operations on its targets
INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000104', 'account:read'),
    ('00000000-0000-7000-8000-000000000104', 'account:discover'),
    ('00000000-0000-7000-8000-000000000104', 'operation:execute');
-- PAM_ADMINISTRATOR, AUDITOR, READ_ONLY_AUDITOR: read
INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000105', 'account:read'),
    ('00000000-0000-7000-8000-000000000106', 'account:read'),
    ('00000000-0000-7000-8000-000000000111', 'account:read');
-- APPLICATION / DATABASE / NETWORK administrators: read accounts on their targets
INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000108', 'account:read'),
    ('00000000-0000-7000-8000-000000000109', 'account:read'),
    ('00000000-0000-7000-8000-000000000110', 'account:read');
