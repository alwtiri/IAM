-- V10 — Provider runtime: capability snapshots and single-use credential handles (PHASE-3-DESIGN §3, §6; G3, G5)

-- What a target can do through a provider instance, as last computed (descriptor + live health). Never simulated.
CREATE TABLE provider.capability_snapshot (
    target_id            uuid        NOT NULL REFERENCES target.target (id),
    provider_instance_id uuid        NOT NULL REFERENCES provider.provider_instance (id),
    capability           text        NOT NULL,
    status               text        NOT NULL CHECK (status IN ('SUPPORTED','UNSUPPORTED','UNAVAILABLE','DEGRADED','AGENT_REQUIRED')),
    reason               text,
    since                timestamptz NOT NULL,
    PRIMARY KEY (target_id, provider_instance_id, capability)
);

-- Credential handles: opaque, single use, short TTL, bound to one operation. Only a SHA-256 of the handle is stored;
-- the secret value itself stays in Vault and is released to the redeeming worker only (ADR-0005).
CREATE TABLE secrets.credential_handle (
    handle_hash   bytea       PRIMARY KEY CHECK (length(handle_hash) = 32),
    operation_id  uuid        NOT NULL REFERENCES operation.operation (id),
    purpose       text        NOT NULL CHECK (purpose ~ '^[a-z][a-z-]{1,31}$'),
    secret_ref    text        NOT NULL CHECK (secret_ref ~ '^vault:[a-z0-9-]+/[a-z0-9/_-]+#[0-9]+$'),
    provider_type text        NOT NULL,
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    redeemed_at   timestamptz,
    redeemed_by   text,
    CONSTRAINT ck_credential_handle_ttl CHECK (expires_at > issued_at AND expires_at <= issued_at + interval '15 minutes')
);
CREATE INDEX ix_credential_handle_operation ON secrets.credential_handle (operation_id);
CREATE INDEX ix_credential_handle_expiry ON secrets.credential_handle (expires_at) WHERE redeemed_at IS NULL;
