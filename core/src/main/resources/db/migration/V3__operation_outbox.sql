-- V3 — Operation model and transactional outbox (spec §46–§48, ADR-0006, ADR-0010)

CREATE TABLE operation.operation (
    id                   uuid        PRIMARY KEY,
    type                 text        NOT NULL,
    mutating             boolean     NOT NULL,
    provider_type        text,
    provider_instance_id uuid,
    target_id            uuid,
    account_id           uuid,
    requester_id         uuid,
    request_id           uuid,
    scope_org_path       text,
    scope_environment    text,
    queue                text,
    status               text        NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCESS','FAILED','TIMEOUT','CANCELLED','PARTIAL','UNKNOWN')),
    status_reason        text,
    progress             integer     NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    attempt              integer     NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    max_attempts         integer     NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    deadline             timestamptz,
    created_at           timestamptz NOT NULL,
    started_at           timestamptz,
    finished_at          timestamptz,
    idempotency_key      text        NOT NULL,
    correlation_id       text,
    error_code           text,
    error_message        text,
    provider_response    jsonb,
    verification_mode    text,
    verified_at          timestamptz,
    verification_summary text,
    version              bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_operation_idempotency UNIQUE (idempotency_key),
    -- G5: a mutating operation can only be SUCCESS with a verification record
    CONSTRAINT ck_operation_verified_success CHECK (
        status <> 'SUCCESS' OR NOT mutating OR (verification_mode IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX ix_operation_status ON operation.operation (status, created_at);
CREATE INDEX ix_operation_target ON operation.operation (target_id);
CREATE INDEX ix_operation_account ON operation.operation (account_id);
CREATE INDEX ix_operation_request ON operation.operation (request_id);
-- one in-flight mutating operation per account (DOMAIN-MODEL §9)
CREATE UNIQUE INDEX uq_operation_account_inflight ON operation.operation (account_id)
    WHERE mutating AND account_id IS NOT NULL AND status IN ('QUEUED', 'RUNNING', 'UNKNOWN', 'PARTIAL');

CREATE TABLE operation.outbox_message (
    id              uuid        PRIMARY KEY,
    destination     text        NOT NULL,
    aggregate_type  text,
    aggregate_id    text,
    payload         jsonb       NOT NULL,
    headers         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED', 'PARKED')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    published_at    timestamptz,
    last_error      text
);
CREATE INDEX ix_outbox_due ON operation.outbox_message (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_parked ON operation.outbox_message (created_at) WHERE status = 'PARKED';

-- Idempotent consumers (Core result consumer from Phase 3)
CREATE TABLE operation.processed_message (
    consumer     text        NOT NULL,
    message_id   uuid        NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
