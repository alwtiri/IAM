-- V9 — Account Management Core (spec §12–§14, G8, PHASE-3-DESIGN §5)
-- Accounts are discovered or created on targets through provider instances. Governance state is platform truth;
-- native state is what the target last reported. No secret values are stored here (credentials live in Vault).

CREATE TABLE account.account (
    id                    uuid        PRIMARY KEY,
    target_id             uuid        NOT NULL REFERENCES target.target (id),
    provider_instance_id  uuid        NOT NULL REFERENCES provider.provider_instance (id),
    native_id             text        NOT NULL,
    name                  text        NOT NULL,
    display_name          text,
    account_type          text        NOT NULL CHECK (account_type IN ('HUMAN','SERVICE','SYSTEM','SHARED','EMERGENCY','UNKNOWN')),
    privileged            boolean     NOT NULL DEFAULT false,
    privilege_reason      text,
    owner_identity_id     uuid        REFERENCES identity.identity (id),
    linked_identity_id    uuid        REFERENCES identity.identity (id),
    governance_state      text        NOT NULL CHECK (governance_state IN ('DISCOVERED','GOVERNED','MANAGED','EXCLUDED','REMOVED')),
    native_status         text        NOT NULL CHECK (native_status IN ('ENABLED','DISABLED','LOCKED','EXPIRED','UNKNOWN','ABSENT')),
    source                text        NOT NULL CHECK (source IN ('DISCOVERY','PLATFORM','IMPORT')),
    attributes            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_seen_at          timestamptz,
    last_login_at         timestamptz,
    password_last_set_at  timestamptz,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_account_native UNIQUE (target_id, native_id),
    CONSTRAINT ck_account_no_secret_attributes CHECK (NOT (attributes ?| ARRAY['password','secret','token','privateKey']))
);
CREATE INDEX ix_account_target ON account.account (target_id);
CREATE INDEX ix_account_provider_instance ON account.account (provider_instance_id);
CREATE INDEX ix_account_governance ON account.account (governance_state);
CREATE INDEX ix_account_linked_identity ON account.account (linked_identity_id);
CREATE INDEX ix_account_owner_identity ON account.account (owner_identity_id);
CREATE INDEX ix_account_name ON account.account (lower(name));

-- Accounts whose credential the platform manages (rotation/checkout in later phases)
CREATE TABLE account.managed_account (
    account_id            uuid        PRIMARY KEY REFERENCES account.account (id),
    credential_secret_ref text        CHECK (credential_secret_ref IS NULL OR credential_secret_ref ~ '^vault:[a-z0-9-]+/[a-z0-9/_-]+#[0-9]+$'),
    rotation_interval_days integer    CHECK (rotation_interval_days IS NULL OR rotation_interval_days BETWEEN 1 AND 3650),
    last_rotated_at       timestamptz,
    dual_control          boolean     NOT NULL DEFAULT false,
    emergency             boolean     NOT NULL DEFAULT false,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);

CREATE TABLE account.entitlement (
    id                   uuid        PRIMARY KEY,
    target_id            uuid        NOT NULL REFERENCES target.target (id),
    provider_instance_id uuid        NOT NULL REFERENCES provider.provider_instance (id),
    native_id            text        NOT NULL,
    name                 text        NOT NULL,
    type                 text        NOT NULL CHECK (type IN ('GROUP','ROLE','PRIVILEGE')),
    privileged           boolean     NOT NULL DEFAULT false,
    description          text,
    last_seen_at         timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT uq_entitlement_native UNIQUE (target_id, native_id)
);
CREATE INDEX ix_entitlement_provider_instance ON account.entitlement (provider_instance_id);

CREATE TABLE account.entitlement_assignment (
    account_id         uuid        NOT NULL REFERENCES account.account (id),
    entitlement_id     uuid        NOT NULL REFERENCES account.entitlement (id),
    actual_state       text        NOT NULL CHECK (actual_state IN ('PRESENT','ABSENT')),
    desired_state      text        CHECK (desired_state IN ('PRESENT','ABSENT')),
    last_reconciled_at timestamptz,
    PRIMARY KEY (account_id, entitlement_id)
);
CREATE INDEX ix_entitlement_assignment_entitlement ON account.entitlement_assignment (entitlement_id);

CREATE TABLE account.discovery_run (
    id                   uuid        PRIMARY KEY,
    provider_instance_id uuid        NOT NULL REFERENCES provider.provider_instance (id),
    target_id            uuid        NOT NULL REFERENCES target.target (id),
    operation_id         uuid,
    status               text        NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at           timestamptz NOT NULL,
    finished_at          timestamptz,
    accounts_seen        integer     NOT NULL DEFAULT 0,
    accounts_new         integer     NOT NULL DEFAULT 0,
    accounts_removed     integer     NOT NULL DEFAULT 0,
    groups_seen          integer     NOT NULL DEFAULT 0,
    error_message        text
);
CREATE INDEX ix_discovery_run_target ON account.discovery_run (target_id, started_at DESC);

-- Findings (spec §14): open findings are unique per (account, type)
CREATE TABLE account.account_finding (
    id          uuid        PRIMARY KEY,
    account_id  uuid        NOT NULL REFERENCES account.account (id),
    type        text        NOT NULL CHECK (type IN ('ORPHAN','UNMANAGED','DORMANT','UNEXPECTED','PRIVILEGED_WITHOUT_OWNER','DISABLED_IDENTITY_ACTIVE_ACCOUNT')),
    severity    text        NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    detected_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by uuid,
    resolution  text,
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE UNIQUE INDEX uq_account_finding_open ON account.account_finding (account_id, type) WHERE resolved_at IS NULL;
CREATE INDEX ix_account_finding_open_type ON account.account_finding (type, severity) WHERE resolved_at IS NULL;
