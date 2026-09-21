-- V7 — Target and provider-instance registry (spec §10–§11, metadata only)

CREATE TABLE target.target (
    id                          uuid        PRIMARY KEY,
    name                        text        NOT NULL,
    hostname                    text,
    ip_address                  inet,
    dns_name                    text,
    type                        text        NOT NULL CHECK (type IN ('LINUX_SERVER','WINDOWS_SERVER','ACTIVE_DIRECTORY','DATABASE',
                                    'APPLICATION','NETWORK_DEVICE','VMWARE','OVM','HPE_3PAR','HPE_STOREONCE','HPE_ONEVIEW',
                                    'CLOUD_PLATFORM','KUBERNETES','CONTAINER_PLATFORM','API_ENDPOINT')),
    platform                    text,
    operating_system            text,
    environment                 text        NOT NULL CHECK (environment IN ('PRODUCTION','STAGING','TEST','DEVELOPMENT','DR')),
    criticality                 text        NOT NULL CHECK (criticality IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    classification              text        NOT NULL CHECK (classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
    owner_org_unit_id           uuid        NOT NULL REFERENCES organization.org_unit (id),
    owner_identity_id           uuid        REFERENCES identity.identity (id),
    technical_owner_identity_id uuid        REFERENCES identity.identity (id),
    business_owner_identity_id  uuid        REFERENCES identity.identity (id),
    location_id                 uuid        REFERENCES organization.location (id),
    tags                        text[]      NOT NULL DEFAULT '{}',
    status                      text        NOT NULL CHECK (status IN ('ACTIVE','MAINTENANCE','DECOMMISSIONED')),
    discovery_state             text        NOT NULL DEFAULT 'NOT_DISCOVERED',
    reconciliation_state        text        NOT NULL DEFAULT 'NOT_RECONCILED',
    health                      text        NOT NULL DEFAULT 'UNKNOWN',
    created_at                  timestamptz NOT NULL,
    updated_at                  timestamptz NOT NULL,
    version                     bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_target_name ON target.target (lower(name));
CREATE INDEX ix_target_type_env ON target.target (type, environment);
CREATE INDEX ix_target_owner_org ON target.target (owner_org_unit_id);
CREATE INDEX ix_target_hostname ON target.target (hostname);
CREATE INDEX ix_target_tags ON target.target USING gin (tags);

CREATE TABLE provider.provider_instance (
    id                    uuid        PRIMARY KEY,
    type                  text        NOT NULL CHECK (type ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    name                  text        NOT NULL,
    endpoint              text        NOT NULL,
    settings              jsonb       NOT NULL DEFAULT '{}'::jsonb,
    credential_secret_ref text        CHECK (credential_secret_ref IS NULL OR credential_secret_ref ~ '^vault:[a-z0-9-]+/[a-z0-9/_-]+#[0-9]+$'),
    enabled               boolean     NOT NULL DEFAULT true,
    health                text        NOT NULL DEFAULT 'UNKNOWN' CHECK (health IN ('HEALTHY','DEGRADED','UNAVAILABLE','UNKNOWN')),
    circuit_state         text        NOT NULL DEFAULT 'CLOSED' CHECK (circuit_state IN ('CLOSED','OPEN','HALF_OPEN')),
    last_health_at        timestamptz,
    failure_reason        text,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    version               bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_provider_instance_name ON provider.provider_instance (lower(name));
CREATE INDEX ix_provider_instance_type ON provider.provider_instance (type);

CREATE TABLE provider.target_binding (
    target_id            uuid NOT NULL REFERENCES target.target (id),
    provider_instance_id uuid NOT NULL REFERENCES provider.provider_instance (id),
    channel              text NOT NULL,
    PRIMARY KEY (target_id, provider_instance_id, channel)
);
