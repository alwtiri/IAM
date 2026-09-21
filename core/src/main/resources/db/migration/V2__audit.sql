-- V2 — Audit subsystem (ADR-0008, DATA-MODEL §2 audit, PHASE-2-DESIGN §3.4)

CREATE TABLE audit.audit_event (
    id                   uuid        PRIMARY KEY,
    chain_partition      text        NOT NULL,
    seq                  bigint      NOT NULL CHECK (seq > 0),
    occurred_at          timestamptz NOT NULL,
    actor_identity_id    uuid,
    actor_type           text        NOT NULL CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM', 'ANONYMOUS')),
    action               text        NOT NULL,
    object_type          text,
    object_id            text,
    target_id            uuid,
    source               text,
    result               text        NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    reason               text,
    request_id           uuid,
    correlation_id       text,
    ip                   inet,
    session_id           text,
    provider_instance_id uuid,
    details              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash            bytea       NOT NULL CHECK (octet_length(prev_hash) = 32),
    hash                 bytea       NOT NULL CHECK (octet_length(hash) = 32),
    schema_version       smallint    NOT NULL DEFAULT 1,
    CONSTRAINT uq_audit_event_chain UNIQUE (chain_partition, seq)
);

CREATE INDEX ix_audit_event_occurred_at ON audit.audit_event (occurred_at);
CREATE INDEX ix_audit_event_actor ON audit.audit_event (actor_identity_id, occurred_at);
CREATE INDEX ix_audit_event_object ON audit.audit_event (object_type, object_id);
CREATE INDEX ix_audit_event_action ON audit.audit_event (action, occurred_at);
CREATE INDEX ix_audit_event_correlation ON audit.audit_event (correlation_id);

CREATE TABLE audit.chain_head (
    chain_partition text        PRIMARY KEY,
    last_seq        bigint      NOT NULL CHECK (last_seq >= 0),
    last_hash       bytea       NOT NULL CHECK (octet_length(last_hash) = 32),
    updated_at      timestamptz NOT NULL
);

-- Signed checkpoints (Vault Transit) are produced from Phase 9; the table is created now.
CREATE TABLE audit.audit_checkpoint (
    id              uuid        PRIMARY KEY,
    chain_partition text        NOT NULL,
    from_seq        bigint      NOT NULL,
    to_seq          bigint      NOT NULL CHECK (to_seq >= from_seq),
    root_hash       bytea       NOT NULL,
    signature       text        NOT NULL,
    key_version     integer     NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE TABLE audit.evidence_link (
    id         uuid        PRIMARY KEY,
    from_type  text        NOT NULL,
    from_id    text        NOT NULL,
    to_type    text        NOT NULL,
    to_id      text        NOT NULL,
    relation   text        NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_evidence_from ON audit.evidence_link (from_type, from_id);
CREATE INDEX ix_evidence_to ON audit.evidence_link (to_type, to_id);

-- Immutability: no UPDATE, DELETE or TRUNCATE on audit records, whoever the user is (defence in depth on top of grants).
CREATE FUNCTION audit.reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit records are immutable (% on %)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_audit_event_no_update BEFORE UPDATE OR DELETE ON audit.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();
CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON audit.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_modification();
CREATE TRIGGER trg_audit_checkpoint_no_update BEFORE UPDATE OR DELETE ON audit.audit_checkpoint
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();
CREATE TRIGGER trg_evidence_link_no_update BEFORE UPDATE OR DELETE ON audit.evidence_link
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();

-- The chain head is the only audit table the application updates.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'iam_app') THEN
        GRANT UPDATE ON audit.chain_head TO iam_app;
    END IF;
END $$;
