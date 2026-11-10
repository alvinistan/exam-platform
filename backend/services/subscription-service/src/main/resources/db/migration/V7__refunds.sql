-- Phase 13: refunds and finance reports.

-- A refund of all or part of a successful payment. PAYHERE_API: sent through PayHere's Refund API (provider_ref is
-- PayHere's refund number). MANUAL: the administrator refunded in PayHere's portal (or by other means) and records it
-- here (provider_ref is whatever reference they noted).
CREATE TABLE refund (
    id              uuid PRIMARY KEY,
    transaction_id  uuid        NOT NULL REFERENCES payment_transaction (id),
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    currency        char(3)     NOT NULL CHECK (currency IN ('LKR', 'USD')),
    reason          text        NOT NULL CHECK (length(reason) BETWEEN 3 AND 500),
    method          text        NOT NULL CHECK (method IN ('PAYHERE_API', 'MANUAL')),
    status          text        NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    provider_ref    text        CHECK (length(provider_ref) <= 100),
    failure_reason  text        CHECK (length(failure_reason) <= 500),
    stop_access     boolean     NOT NULL,
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refund_tx_idx ON refund (transaction_id);
CREATE INDEX refund_time_idx ON refund (created_at DESC);

-- Refunds that are pending or done never add up to more than the payment, whatever the application does.
CREATE FUNCTION refund_within_payment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    paid   bigint;
    total  bigint;
    cur    char(3);
BEGIN
    SELECT amount_minor, currency INTO paid, cur FROM payment_transaction WHERE id = NEW.transaction_id;
    IF NEW.currency <> cur THEN
        RAISE EXCEPTION 'refund currency % differs from the payment (%)', NEW.currency, cur;
    END IF;
    SELECT coalesce(sum(amount_minor), 0) INTO total FROM refund
    WHERE transaction_id = NEW.transaction_id AND status IN ('PENDING', 'SUCCEEDED');
    IF total > paid THEN
        RAISE EXCEPTION 'refunds (%) would exceed the payment (%)', total, paid;
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER refund_within_payment AFTER INSERT OR UPDATE ON refund
    DEFERRABLE INITIALLY IMMEDIATE FOR EACH ROW EXECUTE FUNCTION refund_within_payment();

-- Finance reports and payment search filter by date and status.
CREATE INDEX payment_created_idx ON payment_transaction (created_at DESC);
CREATE INDEX payment_status_created_idx ON payment_transaction (status, created_at DESC);
