-- Phase 11B: plans for one exam, a bundle of exams, or everything; an LKR and a USD price; attempt limits per exam
-- type. What a student buys is copied onto their entitlement, so later plan changes never alter it.

-- Plans ---------------------------------------------------------------------------------------------------------------
ALTER TABLE subscription_plan
    ADD COLUMN scope_type      text    NOT NULL DEFAULT 'ALL' CHECK (scope_type IN ('EXAM', 'BUNDLE', 'ALL')),
    ADD COLUMN category_id     uuid,                                  -- for grouping bundles on the Pricing page
    ADD COLUMN price_lkr_minor bigint  CHECK (price_lkr_minor > 0),
    ADD COLUMN price_usd_minor bigint  CHECK (price_usd_minor > 0),
    -- Attempts per exam type, per exam, for the length of the plan: NULL = unlimited, 0 = not included.
    ADD COLUMN limit_practice  integer CHECK (limit_practice >= 0),
    ADD COLUMN limit_mock      integer CHECK (limit_mock >= 0),
    ADD COLUMN limit_adaptive  integer CHECK (limit_adaptive >= 0);

UPDATE subscription_plan SET
    price_lkr_minor = CASE WHEN currency = 'LKR' THEN price_minor END,
    price_usd_minor = CASE WHEN currency = 'USD' THEN price_minor END;

ALTER TABLE subscription_plan
    DROP CONSTRAINT plan_currency_check,
    DROP COLUMN price_minor,
    DROP COLUMN currency,
    DROP COLUMN scope,
    ADD CONSTRAINT plan_has_price CHECK (price_lkr_minor IS NOT NULL OR price_usd_minor IS NOT NULL);

-- The exams a plan covers: exactly one for EXAM, one or more for BUNDLE, none for ALL (checked by the service).
CREATE TABLE plan_exam (
    plan_id  uuid NOT NULL REFERENCES subscription_plan (id) ON DELETE CASCADE,
    exam_id  uuid NOT NULL,
    PRIMARY KEY (plan_id, exam_id)
);
CREATE INDEX plan_exam_exam_idx ON plan_exam (exam_id);

-- Payments carry what was bought, frozen at checkout -------------------------------------------------------------------
ALTER TABLE payment_transaction
    ADD COLUMN plan_snapshot jsonb;   -- {"scopeType", "examIds", "limits", "durationMonths"}; NULL for older payments

-- Entitlements: their own copy of the scope and limits -------------------------------------------------------------------
ALTER TABLE entitlement
    DROP COLUMN scope,
    ADD COLUMN scope_type     text    NOT NULL DEFAULT 'ALL' CHECK (scope_type IN ('EXAM', 'BUNDLE', 'ALL')),
    ADD COLUMN plan_name      text,
    ADD COLUMN limit_practice integer CHECK (limit_practice >= 0),
    ADD COLUMN limit_mock     integer CHECK (limit_mock >= 0),
    ADD COLUMN limit_adaptive integer CHECK (limit_adaptive >= 0);

CREATE TABLE entitlement_exam (
    entitlement_id  uuid NOT NULL REFERENCES entitlement (id) ON DELETE CASCADE,
    exam_id         uuid NOT NULL,
    PRIMARY KEY (entitlement_id, exam_id)
);
CREATE INDEX entitlement_exam_exam_idx ON entitlement_exam (exam_id);

-- One row per attempt started on an entitlement: which allowance paid for it; never counted twice.
CREATE TABLE entitlement_use (
    attempt_id      uuid PRIMARY KEY,
    entitlement_id  uuid        NOT NULL REFERENCES entitlement (id),
    student_id      uuid        NOT NULL,
    exam_id         uuid        NOT NULL,
    mode            text        NOT NULL CHECK (mode IN ('PRACTICE', 'MOCK', 'USER_FRIENDLY')),
    used_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX entitlement_use_lookup_idx ON entitlement_use (entitlement_id, exam_id, mode);
CREATE INDEX entitlement_use_student_idx ON entitlement_use (student_id, exam_id);
