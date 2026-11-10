-- Phase 11C: sale prices, free access campaigns and discount codes.

-- Sale prices: a lower price between two dates (introductory offers). Either currency may have one.
ALTER TABLE subscription_plan
    ADD COLUMN sale_price_lkr_minor bigint CHECK (sale_price_lkr_minor > 0),
    ADD COLUMN sale_price_usd_minor bigint CHECK (sale_price_usd_minor > 0),
    ADD COLUMN sale_starts_at       timestamptz,
    ADD COLUMN sale_ends_at         timestamptz,
    ADD CONSTRAINT plan_sale_window CHECK (
        (sale_price_lkr_minor IS NULL AND sale_price_usd_minor IS NULL)
        OR (sale_starts_at IS NOT NULL AND sale_ends_at IS NOT NULL AND sale_ends_at > sale_starts_at));

-- Free access campaigns ------------------------------------------------------------------------------------------------
-- ALL: every signed-in student has the access between starts_at and ends_at (a row per student appears when they
-- first use it). SELECTED: chosen students receive it straight away, for duration_months from that day.
CREATE TABLE campaign (
    id               uuid PRIMARY KEY,
    name             text        NOT NULL,
    scope_type       text        NOT NULL CHECK (scope_type IN ('EXAM', 'BUNDLE', 'ALL')),
    category_id      uuid,
    limit_practice   integer     CHECK (limit_practice >= 0),
    limit_mock       integer     CHECK (limit_mock >= 0),
    limit_adaptive   integer     CHECK (limit_adaptive >= 0),
    audience         text        NOT NULL CHECK (audience IN ('ALL', 'SELECTED')),
    starts_at        timestamptz NOT NULL,
    ends_at          timestamptz,                        -- ALL: when the offer closes
    duration_months  smallint    CHECK (duration_months IN (1, 3, 6, 12)),   -- SELECTED: length per student
    ended_at         timestamptz,                        -- ended early by an administrator
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CHECK ((audience = 'ALL') = (ends_at IS NOT NULL)),
    CHECK ((audience = 'SELECTED') = (duration_months IS NOT NULL)),
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE campaign_exam (
    campaign_id  uuid NOT NULL REFERENCES campaign (id) ON DELETE CASCADE,
    exam_id      uuid NOT NULL,
    PRIMARY KEY (campaign_id, exam_id)
);

ALTER TABLE entitlement DROP CONSTRAINT entitlement_source_check;
ALTER TABLE entitlement
    ADD CONSTRAINT entitlement_source_check CHECK (source IN ('SUBSCRIPTION', 'MANUAL', 'CAMPAIGN')),
    ADD COLUMN campaign_id uuid REFERENCES campaign (id),
    ADD CONSTRAINT entitlement_campaign CHECK ((source = 'CAMPAIGN') = (campaign_id IS NOT NULL)),
    ADD CONSTRAINT entitlement_campaign_once UNIQUE (campaign_id, student_id);

-- Discount codes ------------------------------------------------------------------------------------------------------
CREATE TABLE discount_code (
    id                 uuid PRIMARY KEY,
    code               text        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{2,31}$'),
    description        text        NOT NULL DEFAULT '',
    percent_off        integer     CHECK (percent_off BETWEEN 1 AND 100),
    amount_off_lkr_minor bigint    CHECK (amount_off_lkr_minor > 0),
    amount_off_usd_minor bigint    CHECK (amount_off_usd_minor > 0),
    all_plans          boolean     NOT NULL DEFAULT true,
    starts_at          timestamptz NOT NULL,
    ends_at            timestamptz NOT NULL,
    max_uses           integer     CHECK (max_uses > 0),             -- NULL = no overall limit
    max_uses_per_student integer   NOT NULL DEFAULT 1 CHECK (max_uses_per_student > 0),
    active             boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            integer     NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    -- Either a percentage, or a fixed amount in at least one currency.
    CHECK ((percent_off IS NOT NULL) <> (amount_off_lkr_minor IS NOT NULL OR amount_off_usd_minor IS NOT NULL))
);

CREATE TABLE discount_code_plan (
    code_id  uuid NOT NULL REFERENCES discount_code (id) ON DELETE CASCADE,
    plan_id  uuid NOT NULL REFERENCES subscription_plan (id) ON DELETE CASCADE,
    PRIMARY KEY (code_id, plan_id)
);

-- What each payment was charged: list price, any discount code, and the amount (which can now be zero).
ALTER TABLE payment_transaction
    ADD COLUMN list_price_minor bigint,
    ADD COLUMN discount_code_id uuid REFERENCES discount_code (id),
    ADD COLUMN discount_minor   bigint NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    DROP CONSTRAINT payment_transaction_amount_minor_check,
    ADD CONSTRAINT payment_amount_not_negative CHECK (amount_minor >= 0),
    DROP CONSTRAINT payment_provider_check,
    -- FREE: a code that brings the price to zero; activated by the server without a payment provider.
    ADD CONSTRAINT payment_provider_check CHECK (provider IN ('PAYHERE', 'TEST', 'FREE'));
CREATE INDEX payment_code_idx ON payment_transaction (discount_code_id, student_id) WHERE discount_code_id IS NOT NULL;
