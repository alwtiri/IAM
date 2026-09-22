-- V12 — Phase 4: policy decisions for access requests (ADR-0013), SoD rules, access requests with approval steps.

-- ------------------------------------------------------------------ policy
CREATE TABLE policy.policy (
    id                     uuid        PRIMARY KEY,
    code                   text        NOT NULL UNIQUE,
    name                   text        NOT NULL,
    description            text,
    enabled                boolean     NOT NULL DEFAULT true,
    effect                 text        NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    request_type           text        NOT NULL DEFAULT 'ROLE' CHECK (request_type IN ('ROLE')),
    role_codes             text[],                         -- NULL = any role
    identity_types         text[],                         -- NULL = any identity type
    approvals              text[]      NOT NULL DEFAULT '{}', -- ordered steps: MANAGER | ROLE:<code>
    require_justification  boolean     NOT NULL DEFAULT false,
    max_duration_days      integer     CHECK (max_duration_days IS NULL OR max_duration_days BETWEEN 1 AND 3650),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint      NOT NULL DEFAULT 0
);

INSERT INTO policy.policy (id, code, name, description, effect, role_codes, identity_types, approvals, require_justification, max_duration_days) VALUES
 ('00000000-0000-7000-8000-000000000401', 'P-100', 'Standard role requests',
  'Any role can be requested with a justification; the manager approves; at most 180 days.',
  'ALLOW', NULL, NULL, '{MANAGER}', true, 180),
 ('00000000-0000-7000-8000-000000000402', 'P-200', 'Privileged roles',
  'Administrative roles need the manager and a security administrator; at most 30 days.',
  'ALLOW', '{PLATFORM_ADMINISTRATOR,SECURITY_ADMINISTRATOR,PAM_ADMINISTRATOR,INFRASTRUCTURE_ADMINISTRATOR,IAM_ADMINISTRATOR}', NULL,
  '{MANAGER,ROLE:SECURITY_ADMINISTRATOR}', true, 30),
 ('00000000-0000-7000-8000-000000000403', 'P-900', 'No platform or security administration for external identities',
  'Contractors and external identities cannot receive platform or security administrator roles.',
  'DENY', '{PLATFORM_ADMINISTRATOR,SECURITY_ADMINISTRATOR}', '{CONTRACTOR,EXTERNAL}', '{}', false, NULL);

-- ------------------------------------------------------------------ SoD
CREATE TABLE sod.rule (
    id          uuid        PRIMARY KEY,
    code        text        NOT NULL UNIQUE,
    name        text        NOT NULL,
    left_role   text        NOT NULL,
    right_role  text        NOT NULL,
    mode        text        NOT NULL CHECK (mode IN ('PREVENTIVE', 'DETECTIVE')),
    severity    text        NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    enabled     boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sod_distinct CHECK (left_role <> right_role)
);

INSERT INTO sod.rule (id, code, name, left_role, right_role, mode, severity) VALUES
 ('00000000-0000-7000-8000-000000000501', 'SOD-01', 'Auditors cannot administer the platform', 'AUDITOR', 'PLATFORM_ADMINISTRATOR', 'PREVENTIVE', 'HIGH'),
 ('00000000-0000-7000-8000-000000000502', 'SOD-02', 'Auditors cannot administer identities', 'AUDITOR', 'IAM_ADMINISTRATOR', 'PREVENTIVE', 'HIGH'),
 ('00000000-0000-7000-8000-000000000503', 'SOD-03', 'Read-only auditors cannot administer the platform', 'READ_ONLY_AUDITOR', 'PLATFORM_ADMINISTRATOR', 'PREVENTIVE', 'HIGH'),
 ('00000000-0000-7000-8000-000000000504', 'SOD-04', 'Auditors should not operate infrastructure', 'AUDITOR', 'INFRASTRUCTURE_ADMINISTRATOR', 'DETECTIVE', 'MEDIUM'),
 ('00000000-0000-7000-8000-000000000505', 'SOD-05', 'Security oversight separate from privileged access administration', 'SECURITY_ADMINISTRATOR', 'PAM_ADMINISTRATOR', 'DETECTIVE', 'MEDIUM');

-- ------------------------------------------------------------------ access requests
CREATE TABLE request.access_request (
    id                  uuid        PRIMARY KEY,
    requester_id        uuid        NOT NULL,
    beneficiary_id      uuid        NOT NULL,
    type                text        NOT NULL DEFAULT 'ROLE' CHECK (type IN ('ROLE')),
    role_id             uuid        NOT NULL,
    role_code           text        NOT NULL,
    scope_type          text        NOT NULL CHECK (scope_type IN ('GLOBAL', 'ORG_UNIT')),
    scope_value         text        NOT NULL,
    justification       text,
    duration_days       integer     NOT NULL CHECK (duration_days BETWEEN 1 AND 3650),
    status              text        NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED', 'ACTIVE',
                                                               'FAILED', 'EXPIRED', 'REVOKED')),
    status_reason       text,
    decision            jsonb       NOT NULL,             -- policy decision snapshot (matched policies, obligations)
    sod_conflicts       jsonb       NOT NULL DEFAULT '[]',
    role_assignment_id  uuid,
    valid_until         timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_access_request_requester ON request.access_request (requester_id, created_at DESC);
CREATE INDEX ix_access_request_status ON request.access_request (status, created_at DESC);
CREATE UNIQUE INDEX ux_access_request_assignment ON request.access_request (role_assignment_id) WHERE role_assignment_id IS NOT NULL;
-- One open request per beneficiary and role
CREATE UNIQUE INDEX ux_access_request_open ON request.access_request (beneficiary_id, role_id)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED');

CREATE TABLE request.approval_step (
    request_id           uuid        NOT NULL REFERENCES request.access_request (id),
    step_no              integer     NOT NULL,
    approver_type        text        NOT NULL CHECK (approver_type IN ('MANAGER', 'ROLE')),
    approver_role        text,
    approver_identity_id uuid,
    status               text        NOT NULL CHECK (status IN ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'SKIPPED')),
    decided_by           uuid,
    decided_at           timestamptz,
    comment              text,
    auth_context         text,
    note                 text,
    PRIMARY KEY (request_id, step_no),
    CONSTRAINT ck_step_target CHECK ((approver_type = 'MANAGER' AND approver_identity_id IS NOT NULL)
                                     OR (approver_type = 'ROLE' AND approver_role IS NOT NULL)),
    CONSTRAINT ck_step_decision CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL))
);
CREATE INDEX ix_approval_step_pending ON request.approval_step (status, approver_identity_id, approver_role);

-- ------------------------------------------------------------------ permissions
INSERT INTO "authorization".permission (code, description) VALUES
    ('policy:read', 'Read access policies'),
    ('policy:write', 'Enable or disable access policies'),
    ('sod:read', 'Read separation-of-duties rules'),
    ('request:read', 'Read all access requests (oversight)');

INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000101', 'policy:read'),
    ('00000000-0000-7000-8000-000000000101', 'policy:write'),
    ('00000000-0000-7000-8000-000000000101', 'sod:read'),
    ('00000000-0000-7000-8000-000000000101', 'request:read'),
    ('00000000-0000-7000-8000-000000000103', 'policy:read'),
    ('00000000-0000-7000-8000-000000000103', 'policy:write'),
    ('00000000-0000-7000-8000-000000000103', 'sod:read'),
    ('00000000-0000-7000-8000-000000000103', 'request:read'),
    ('00000000-0000-7000-8000-000000000102', 'policy:read'),
    ('00000000-0000-7000-8000-000000000102', 'sod:read'),
    ('00000000-0000-7000-8000-000000000102', 'request:read'),
    ('00000000-0000-7000-8000-000000000106', 'policy:read'),
    ('00000000-0000-7000-8000-000000000106', 'sod:read'),
    ('00000000-0000-7000-8000-000000000106', 'request:read'),
    ('00000000-0000-7000-8000-000000000111', 'policy:read'),
    ('00000000-0000-7000-8000-000000000111', 'sod:read'),
    ('00000000-0000-7000-8000-000000000111', 'request:read');
