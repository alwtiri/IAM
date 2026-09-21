-- V4 — Organization model (spec §5, DATA-MODEL §2 organization)

CREATE TABLE organization.organization (
    id         uuid        PRIMARY KEY,
    code       text        NOT NULL UNIQUE,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Single organization per deployment (Phase 0 G-02); id matches SystemIdentities.DEFAULT_ORGANIZATION_ID.
INSERT INTO organization.organization (id, code, name) VALUES ('00000000-0000-7000-8000-000000000001', 'ORG', 'Organization');

CREATE TABLE organization.org_unit (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization.organization (id),
    parent_id       uuid        REFERENCES organization.org_unit (id),
    kind            text        NOT NULL CHECK (kind IN ('BUSINESS_UNIT', 'DEPARTMENT', 'TEAM')),
    code            text        NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    path            text        NOT NULL UNIQUE,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    archived_at     timestamptz,
    CONSTRAINT uq_org_unit_code UNIQUE (organization_id, code),
    CONSTRAINT ck_org_unit_path CHECK (path LIKE '%/' || id::text || '/')
);
CREATE INDEX ix_org_unit_parent ON organization.org_unit (parent_id);
CREATE INDEX ix_org_unit_path ON organization.org_unit (path text_pattern_ops);

CREATE TABLE organization.position (
    id         uuid        PRIMARY KEY,
    code       text        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    name       text        NOT NULL,
    detail     text,
    created_at timestamptz NOT NULL
);

CREATE TABLE organization.location (
    id         uuid        PRIMARY KEY,
    code       text        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    name       text        NOT NULL,
    detail     text,
    created_at timestamptz NOT NULL
);
