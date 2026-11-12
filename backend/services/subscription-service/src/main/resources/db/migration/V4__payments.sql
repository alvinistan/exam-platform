-- Phase 11: subscriptions and payments (ADR-011, PayHere).

-- What was bought is copied at checkout, so a later price or plan change never alters a payment in flight.
ALTER TABLE payment_transaction
    ADD COLUMN plan_name       text,
    ADD COLUMN duration_months smallint CHECK (duration_months IN (1, 3, 6, 12));
UPDATE payment_transaction t SET plan_name = p.name, duration_months = p.duration_months
FROM subscription_plan p WHERE p.id = t.plan_id;
ALTER TABLE payment_transaction
    ALTER COLUMN plan_name SET NOT NULL,
    ALTER COLUMN duration_months SET NOT NULL,
    ADD CONSTRAINT payment_provider_check CHECK (provider IN ('PAYHERE', 'TEST'));

CREATE INDEX payment_student_idx ON payment_transaction (student_id, created_at DESC);
CREATE INDEX subscription_active_end_idx ON subscription (ends_at) WHERE status = 'ACTIVE';

-- PayHere accepts LKR and USD only.
ALTER TABLE subscription_plan ADD CONSTRAINT plan_currency_check CHECK (currency IN ('LKR', 'USD'));
