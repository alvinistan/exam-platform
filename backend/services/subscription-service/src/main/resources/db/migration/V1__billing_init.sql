-- subscription-service · database: billing · owner: subscription-service only
-- Free-access policies, plans, payments, webhooks, subscriptions, entitlements, audit.

CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;

-- Configurable free-access rules (FA-1 .. FA-7). params examples:
--   FA1_CATALOGUE_POSITION: {"count": 6, "scope": "GLOBAL", "orderBy": "DISPLAY_ORDER"}
--   FA2_WELCOME_ALLOWANCE:  {"attempts": 2}
--   FA4_FREE_FEATURES:      {"modes": ["PRACTICE","MOCK","USER_FRIENDLY"]}
CREATE TABLE free_access_policy (
    rule_code   text PRIMARY KEY CHECK (rule_code IN ('FA1_CATALOGUE_POSITION', 'FA2_WELCOME_ALLOWANCE', 'FA4_FREE_FEATURES')),
    active      boolean     NOT NULL,
    params      jsonb       NOT NULL,
    updated_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 0
);
INSERT INTO free_access_policy (rule_code, active, params) VALUES
    ('FA1_CATALOGUE_POSITION', true,  '{"count": 6, "scope": "GLOBAL", "orderBy": "DISPLAY_ORDER"}'),
    ('FA2_WELCOME_ALLOWANCE',  false, '{"attempts": 2}'),
    ('FA4_FREE_FEATURES',      true,  '{"modes": ["PRACTICE", "MOCK", "USER_FRIENDLY"]}');

-- Read model of the catalogue, fed by catalog-service events; used to evaluate FA-1 without cross-service DB access.
CREATE TABLE exam_view (
    exam_id        uuid PRIMARY KEY,
    category_id    uuid        NOT NULL,
    subject_ids    uuid[]      NOT NULL DEFAULT '{}',
    display_order  integer,
    status         text        NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_at   timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX exam_view_order_idx ON exam_view (display_order) WHERE status = 'PUBLISHED';

-- Welcome-allowance usage: one row per counted attempt, so double counting is impossible (FA-5).
CREATE TABLE allowance_usage (
    student_id  uuid        NOT NULL,
    rule_code   text        NOT NULL,
    attempt_id  uuid        NOT NULL,
    exam_id     uuid        NOT NULL,
    used_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_code, attempt_id)
);
CREATE INDEX allowance_usage_student_idx ON allowance_usage (student_id, rule_code);

CREATE TABLE subscription_plan (
    id              uuid PRIMARY KEY,
    code            text        NOT NULL UNIQUE,
    name            text        NOT NULL,
    description     text        NOT NULL DEFAULT '',
    duration_months smallint    NOT NULL CHECK (duration_months IN (1, 3, 6, 12)),
    price_minor     bigint      NOT NULL CHECK (price_minor > 0),            -- SB-7: smallest currency unit
    currency        char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    active          boolean     NOT NULL DEFAULT false,
    display_order   integer     NOT NULL DEFAULT 0,
    scope           jsonb       NOT NULL DEFAULT '{"type": "ALL_PREMIUM"}'::jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         integer     NOT NULL DEFAULT 0
);

CREATE TABLE payment_transaction (
    id               uuid PRIMARY KEY,
    student_id       uuid        NOT NULL,
    plan_id          uuid        NOT NULL REFERENCES subscription_plan (id),
    provider         text        NOT NULL,                       -- 'SANDBOX' outside production
    provider_ref     text        UNIQUE,                         -- provider checkout/session id
    idempotency_key  text        NOT NULL,
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),  -- price copied at checkout time
    currency         char(3)     NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    failure_reason   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    version          integer     NOT NULL DEFAULT 0,
    UNIQUE (student_id, idempotency_key)
);
CREATE INDEX payment_pending_idx ON payment_transaction (created_at) WHERE status = 'PENDING';

-- Every verified webhook is recorded once; duplicates are acknowledged and ignored (SB-9).
CREATE TABLE webhook_event (
    provider           text        NOT NULL,
    provider_event_id  text        NOT NULL,
    event_type         text        NOT NULL,
    payload_sha256     bytea       NOT NULL,
    received_at        timestamptz NOT NULL DEFAULT now(),
    processed_at       timestamptz,
    outcome            text,
    PRIMARY KEY (provider, provider_event_id)
);

CREATE TABLE subscription (
    id              uuid PRIMARY KEY,
    student_id      uuid        NOT NULL,
    plan_id         uuid        NOT NULL REFERENCES subscription_plan (id),
    transaction_id  uuid        NOT NULL UNIQUE REFERENCES payment_transaction (id),   -- one payment → one period
    status          text        NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED')),
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
CREATE INDEX subscription_student_idx ON subscription (student_id, ends_at DESC);

-- What actually grants access. Active = now() in [starts_at, ends_at) and not revoked; checked at request time (FR-SUB-08).
CREATE TABLE entitlement (
    id               uuid PRIMARY KEY,
    student_id       uuid        NOT NULL,
    source           text        NOT NULL CHECK (source IN ('SUBSCRIPTION', 'MANUAL')),
    subscription_id  uuid        UNIQUE REFERENCES subscription (id),
    scope            jsonb       NOT NULL DEFAULT '{"type": "ALL_PREMIUM"}'::jsonb,
    starts_at        timestamptz NOT NULL,
    ends_at          timestamptz NOT NULL,
    revoked_at       timestamptz,
    revoked_reason   text,
    granted_by       uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK ((source = 'SUBSCRIPTION') = (subscription_id IS NOT NULL)),
    CHECK (source = 'SUBSCRIPTION' OR granted_by IS NOT NULL),
    CHECK (revoked_at IS NULL OR revoked_reason IS NOT NULL)
);
CREATE INDEX entitlement_lookup_idx ON entitlement (student_id, ends_at) WHERE revoked_at IS NULL;

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
CREATE INDEX audit_event_time_idx ON audit_event (created_at DESC);
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TABLE processed_event (
    event_id      uuid PRIMARY KEY,
    source        text        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE feed_cursor (
    source     text PRIMARY KEY,
    last_seq   bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_event (
    seq            bigserial PRIMARY KEY,
    id             uuid        NOT NULL UNIQUE,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,                     -- SubscriptionActivated, SubscriptionExpired
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);
