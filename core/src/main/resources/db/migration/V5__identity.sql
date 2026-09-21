-- V5 — Person, Identity, Platform User (spec §6, DATA-MODEL §2 identity)

CREATE TABLE identity.person (
    id                uuid        PRIMARY KEY,
    org_unit_id       uuid        REFERENCES organization.org_unit (id),
    employee_id       text        UNIQUE,
    given_name        text        NOT NULL,
    family_name       text        NOT NULL,
    display_name      text        NOT NULL,
    position_id       uuid        REFERENCES organization.position (id),
    location_id       uuid        REFERENCES organization.location (id),
    manager_person_id uuid        REFERENCES identity.person (id),
    employment_status text        NOT NULL CHECK (employment_status IN ('PRE_HIRE', 'ACTIVE', 'ON_LEAVE', 'TERMINATED')),
    start_date        date,
    end_date          date,
    email             text,
    phone             text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    archived_at       timestamptz,
    CONSTRAINT ck_person_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_person_not_own_manager CHECK (manager_person_id IS NULL OR manager_person_id <> id)
);
CREATE INDEX ix_person_org_unit ON identity.person (org_unit_id);
CREATE INDEX ix_person_manager ON identity.person (manager_person_id);
CREATE INDEX ix_person_display_name ON identity.person (lower(display_name));

CREATE TABLE identity.identity (
    id           uuid        PRIMARY KEY,
    person_id    uuid        NOT NULL REFERENCES identity.person (id),
    type         text        NOT NULL CHECK (type IN ('EMPLOYEE','CONTRACTOR','CONSULTANT','SERVICE','SYSTEM','EMERGENCY','TEMPORARY','EXTERNAL')),
    username     text        NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    state        text        NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED', 'ARCHIVED')),
    state_reason text,
    valid_from   timestamptz NOT NULL,
    valid_until  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_identity_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_identity_type_validity CHECK (
        type NOT IN ('EMERGENCY', 'TEMPORARY', 'CONTRACTOR', 'EXTERNAL') OR valid_until IS NOT NULL)
);
CREATE INDEX ix_identity_person ON identity.identity (person_id);
CREATE INDEX ix_identity_expiry ON identity.identity (valid_until) WHERE state IN ('PENDING', 'ACTIVE', 'SUSPENDED');

CREATE TABLE identity.platform_user (
    identity_id      uuid        PRIMARY KEY REFERENCES identity.identity (id),
    keycloak_subject text        NOT NULL UNIQUE,
    created_at       timestamptz NOT NULL,
    last_login_at    timestamptz
);

-- Platform settings (bootstrap marker, ADR-0016)
CREATE TABLE identity.platform_setting (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    created_at timestamptz NOT NULL
);

-- SYSTEM person and identity for scheduled jobs (SystemIdentities.SYSTEM_IDENTITY_ID). Never linked to a login.
INSERT INTO identity.person (id, given_name, family_name, display_name, employment_status, created_at, updated_at)
VALUES ('00000000-0000-7000-8000-000000000002', 'Platform', 'System', 'Platform System', 'ACTIVE', now(), now());
INSERT INTO identity.identity (id, person_id, type, username, state, valid_from, created_at, updated_at)
VALUES ('00000000-0000-7000-8000-000000000003', '00000000-0000-7000-8000-000000000002', 'SYSTEM', 'system', 'ACTIVE', now(), now(), now());

CREATE FUNCTION identity.forbid_system_platform_user() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.identity_id = '00000000-0000-7000-8000-000000000003' THEN
        RAISE EXCEPTION 'the SYSTEM identity cannot have a platform login' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_platform_user_not_system BEFORE INSERT OR UPDATE ON identity.platform_user
    FOR EACH ROW EXECUTE FUNCTION identity.forbid_system_platform_user();
