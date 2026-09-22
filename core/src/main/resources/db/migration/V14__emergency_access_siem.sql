-- V14 — Phase 6.2 emergency (break-glass) access and Phase 8 SIEM forwarding.

-- ------------------------------------------------------------------ emergency access
-- account.managed_account.emergency (V9) marks break-glass accounts.
ALTER TABLE account.credential_checkout
    ADD COLUMN emergency     boolean NOT NULL DEFAULT false,
    ADD COLUMN review_status text CHECK (review_status IS NULL OR review_status IN ('PENDING', 'REVIEWED')),
    ADD COLUMN reviewed_by   uuid,
    ADD COLUMN reviewed_at   timestamptz,
    ADD COLUMN review_note   text,
    ADD CONSTRAINT ck_checkout_review CHECK ((emergency AND review_status IS NOT NULL) OR (NOT emergency AND review_status IS NULL));
CREATE INDEX ix_checkout_emergency_review ON account.credential_checkout (review_status, started_at DESC) WHERE emergency;

INSERT INTO "authorization".permission (code, description) VALUES
    ('emergency:access', 'Break-glass: check out an emergency account without approval (always reviewed afterwards)'),
    ('emergency:review', 'Review uses of emergency access');

INSERT INTO "authorization".role_permission (role_id, permission_code) VALUES
    ('00000000-0000-7000-8000-000000000101', 'emergency:access'),
    ('00000000-0000-7000-8000-000000000104', 'emergency:access'),
    ('00000000-0000-7000-8000-000000000105', 'emergency:access'),
    ('00000000-0000-7000-8000-000000000101', 'emergency:review'),
    ('00000000-0000-7000-8000-000000000103', 'emergency:review');

-- ------------------------------------------------------------------ SIEM forwarding (audit events → webhook)
CREATE TABLE audit.forwarding_state (
    sink            text        PRIMARY KEY,
    last_occurred_at timestamptz,
    last_id         uuid,
    forwarded_total bigint      NOT NULL DEFAULT 0,
    last_success_at timestamptz,
    last_error      text,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
