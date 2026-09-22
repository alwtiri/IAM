-- V13 — Phase 6.1: privileged credential vault (rotation with verification) and time-bound credential checkout (ADR-0021).
-- Passwords are only in Vault; these tables hold references, states and the checkout history.

-- ------------------------------------------------------------------ vaulted credentials
ALTER TABLE account.managed_account
    ADD COLUMN secret_path           text,
    ADD COLUMN pending_secret_ref    text CHECK (pending_secret_ref IS NULL OR pending_secret_ref ~ '^vault:[a-z0-9-]+/[a-z0-9/_-]+#[0-9]+$'),
    ADD COLUMN rotation_status       text NOT NULL DEFAULT 'NONE'
        CHECK (rotation_status IN ('NONE', 'ROTATING', 'VERIFIED', 'UNKNOWN', 'FAILED')),
    ADD COLUMN rotation_operation_id uuid,
    ADD COLUMN rotation_trigger      text,
    ADD COLUMN last_rotation_error   text,
    ADD COLUMN managed_by            uuid,
    ADD COLUMN version               bigint NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX ux_managed_account_rotation_op ON account.managed_account (rotation_operation_id) WHERE rotation_operation_id IS NOT NULL;

-- ------------------------------------------------------------------ checkouts (exclusive: one active checkout per account)
CREATE TABLE account.credential_checkout (
    id               uuid        PRIMARY KEY,
    account_id       uuid        NOT NULL REFERENCES account.account (id),
    identity_id      uuid        NOT NULL,
    request_id       uuid,
    reason           text,
    started_at       timestamptz NOT NULL,
    not_after        timestamptz NOT NULL,
    status           text        NOT NULL CHECK (status IN ('ACTIVE', 'CHECKED_IN', 'EXPIRED', 'REVOKED')),
    ended_at         timestamptz,
    ended_by         uuid,
    reveal_count     integer     NOT NULL DEFAULT 0,
    last_revealed_at timestamptz,
    CONSTRAINT ck_checkout_window CHECK (not_after > started_at),
    CONSTRAINT ck_checkout_end CHECK ((status = 'ACTIVE') = (ended_at IS NULL))
);
CREATE UNIQUE INDEX ux_checkout_active ON account.credential_checkout (account_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_checkout_identity ON account.credential_checkout (identity_id, started_at DESC);
CREATE INDEX ix_checkout_expiry ON account.credential_checkout (not_after) WHERE status = 'ACTIVE';

-- ------------------------------------------------------------------ credential requests
ALTER TABLE request.access_request ALTER COLUMN role_id DROP NOT NULL;
ALTER TABLE request.access_request DROP CONSTRAINT access_request_type_check;
ALTER TABLE request.access_request ADD CONSTRAINT access_request_type_check CHECK (type IN ('ROLE', 'CREDENTIAL'));
ALTER TABLE request.access_request
    ADD COLUMN account_id     uuid,
    ADD COLUMN duration_hours integer CHECK (duration_hours IS NULL OR duration_hours BETWEEN 1 AND 72);
ALTER TABLE request.access_request ADD CONSTRAINT ck_access_request_subject CHECK (
    (type = 'ROLE' AND role_id IS NOT NULL) OR (type = 'CREDENTIAL' AND account_id IS NOT NULL AND duration_hours IS NOT NULL));
CREATE UNIQUE INDEX ux_access_request_open_credential ON request.access_request (beneficiary_id, account_id)
    WHERE type = 'CREDENTIAL' AND status IN ('PENDING_APPROVAL', 'APPROVED');
COMMENT ON COLUMN request.access_request.role_assignment_id IS 'Fulfilment: role assignment (ROLE) or credential checkout (CREDENTIAL)';

ALTER TABLE policy.policy DROP CONSTRAINT policy_request_type_check;
ALTER TABLE policy.policy ADD CONSTRAINT policy_request_type_check CHECK (request_type IN ('ROLE', 'CREDENTIAL'));

INSERT INTO policy.policy (id, code, name, description, effect, request_type, role_codes, identity_types, approvals, require_justification,
                           max_duration_days) VALUES
 ('00000000-0000-7000-8000-000000000404', 'P-300', 'Privileged credential checkout',
  'Checking out a vaulted privileged password needs a justification and a PAM administrator''s approval; at most one day. The password is rotated at check-in or expiry.',
  'ALLOW', 'CREDENTIAL', NULL, NULL, '{ROLE:PAM_ADMINISTRATOR}', true, 1),
 ('00000000-0000-7000-8000-000000000405', 'P-910', 'No credential checkout for external identities',
  'Contractors and external identities cannot check out privileged passwords.',
  'DENY', 'CREDENTIAL', NULL, '{CONTRACTOR,EXTERNAL}', '{}', false, NULL);

-- ------------------------------------------------------------------ permissions
INSERT INTO "authorization".permission (code, description) VALUES
    ('credential:manage', 'Vault privileged passwords, rotate them, check them out directly and end checkouts');

INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000101', 'credential:manage'),
    ('00000000-0000-7000-8000-000000000105', 'credential:manage');
