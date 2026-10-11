-- identity-service · database: identity · owner: identity-service only
-- Users, roles, refresh-token sessions, audit, outbox.

CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE app_user (
    id              uuid PRIMARY KEY,
    google_subject  text        NOT NULL UNIQUE,              -- OIDC "sub"; stable Google account id
    email           citext      NOT NULL UNIQUE,
    display_name    text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 120),
    photo_url       text,
    status          text        NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    preferences     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    last_login_at   timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,
    version         integer     NOT NULL DEFAULT 0            -- optimistic locking (@Version)
);

CREATE TABLE user_role (
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role        text        NOT NULL CHECK (role IN ('STUDENT', 'ADMIN')),
    granted_by  uuid        REFERENCES app_user (id),
    granted_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

-- Rotating refresh tokens. Only a SHA-256 hash is stored; reuse of a rotated token revokes the whole family.
CREATE TABLE refresh_token (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id    uuid        NOT NULL,
    token_hash   bytea       NOT NULL UNIQUE,
    client_type  text        NOT NULL CHECK (client_type IN ('WEB', 'MOBILE')),
    issued_at    timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid        REFERENCES refresh_token (id),
    user_agent   text,
    CHECK (expires_at > issued_at)
);
CREATE INDEX refresh_token_user_idx   ON refresh_token (user_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

-- One-time bootstrap of the first administrator (FR-ID-04); the setup command records its run here.
CREATE TABLE admin_bootstrap (
    id          smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    email       citext      NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT now()
);

-- Append-only audit log (FR-ID-08, FR-ADM-04).
CREATE TABLE audit_event (
    id              uuid PRIMARY KEY,
    actor_id        uuid,
    action          text        NOT NULL,
    target_type     text        NOT NULL,
    target_id       text,
    summary         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_time_idx   ON audit_event (created_at DESC);
CREATE INDEX audit_event_target_idx ON audit_event (target_type, target_id);

CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

-- Transactional outbox, exposed to other services as a pull feed (ADR-006).
CREATE TABLE outbox_event (
    seq            bigserial PRIMARY KEY,
    id             uuid        NOT NULL UNIQUE,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);
