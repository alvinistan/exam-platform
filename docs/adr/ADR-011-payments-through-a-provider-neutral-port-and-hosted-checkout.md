# ADR-011: Payments through a provider-neutral port and hosted checkout

- **Status:** Accepted (Phase 3, 11 Oct 2026); amended in Phase 11 (26 Oct 2026) for PayHere
- **Deciders:** Product owner (client), development lead

## Context

The provider and currency are not decided (D8); card data must never touch the platform.

## Decision

subscription-service defines a PaymentProvider port (createCheckout, verifyWebhook). Adapters: Sandbox (labelled, refused by the production profile at startup), then the chosen provider. Activation happens only on a verified webhook (signature over raw body, 5-minute timestamp tolerance), recorded once per provider event id, in one transaction with the subscription and entitlement.

## Consequences

PCI scope stays with the provider (SAQ-A). Switching providers is an adapter change.

## Alternatives considered

Embedding card fields (rejected: PCI scope); activating on the redirect back (rejected: forgeable).

## Amendment — Phase 11 (26 Oct 2026): PayHere

The product owner chose **PayHere** (D8). How the decision is applied:

- **Checkout** is PayHere's hosted page (Checkout API). The server creates a PENDING payment, copying the plan's name,
  length and price, and returns the form fields the browser posts to PayHere, including
  `hash = UPPER(MD5(merchant_id + order_id + amount + currency + UPPER(MD5(merchant_secret))))`. The merchant secret
  never leaves the server. The same `Idempotency-Key` returns the same payment.
- **Verification**: PayHere posts a form to `notify_url` (`/api/payments/webhooks/payhere`) signed with
  `md5sig = UPPER(MD5(merchant_id + order_id + payhere_amount + payhere_currency + status_code + UPPER(MD5(secret))))`.
  PayHere's scheme has no timestamp, so the 5-minute tolerance doesn't apply; replays are harmless because each
  message (order + status + payment id) is recorded once, and amount and currency must match the payment.
- **Outcomes**: 2 activates (subscription period + entitlement + `SubscriptionActivated` outbox event + audit, one
  transaction); 0 stays pending; -1/-2 mark a pending payment cancelled/failed; -3 (charge-back) cancels the period and
  revokes the entitlement. A success that arrives after a checkout was marked abandoned still activates.
- **Renewals** start when the student's latest Premium ends, so paid time is never lost. Nothing renews automatically.
- **The TEST provider** replaces the planned Sandbox adapter: a labelled page where the student chooses the outcome,
  for development and demos. It is the default when no provider is configured and is refused under the `prod`
  profile; PAYHERE without merchant credentials, or live PayHere without an https `PUBLIC_BASE_URL`, also fails startup.
- The provider "port" is kept deliberately small (checkout fields + verified `Notification`); a second provider would
  add one adapter and its own webhook endpoint.

## Amendment — Phase 11B (9 Nov 2026): what a plan covers

- **Plans** cover one exam, a fixed bundle of exams (grouped under a category for display), or every exam; carry an
  LKR and/or a USD price; and set attempts per exam type, per exam: unlimited, a number, or not included.
- **Frozen at checkout**: the payment stores a copy of the plan (scope, exam list, limits, length), and activation
  copies it onto the entitlement. Later plan edits, and exams added to a category, never change what was bought.
- **Plans start straight away** (replacing "renewals start when the current Premium ends"): plans held at the same
  time add up.
- **Which allowance pays for an attempt** (assessment-service asks subscription-service, as before): an unlimited plan
  covering the exam; else the limited plan with attempts left that ends soonest; else a free attempt. Each attempt is
  recorded once against one allowance (`entitlement_use` / `free_attempt_use`), so retries and parallel starts can't
  overspend.
- A plan with payments can't be deleted, only hidden.

## Amendment — Phase 11C (9 Nov 2026): promotions

- **Price at checkout** = the plan's regular price, or its sale price while the sale is on, less a discount code. It is
  worked out by the server at checkout (the quote endpoint is only for display) and recorded on the payment
  (`list_price_minor`, `discount_code_id`, `discount_minor`, `amount_minor`). PayHere's notification is checked against
  `amount_minor`, so a changed price can't be paid at the old amount.
- **Discount code uses** are payments with the code that are PENDING or SUCCEEDED. Checkouts with the same code are
  serialised by locking the code row, so the limits can't be overspent by parallel checkouts. A pending payment holds
  its use until it fails or is abandoned (24 h).
- **Free with a code**: when the total is 0, the payment is created with provider `FREE` and applied at once through
  the same `apply()` path as a verified provider message (event id `<tx>:FREE`), so activation, entitlements, the
  outbox and the audit are identical. No buyer details are needed; PayHere still requires them.
- **Campaigns** grant access through the same entitlements (`source = CAMPAIGN`) and limits as plans. Campaigns for
  chosen students create entitlements at once. Campaigns for everyone are "virtual" grants until a student's first
  counted start creates their entitlement row (`UNIQUE (campaign_id, student_id)`), so a campaign for thousands of
  students writes nothing up front. Ending a campaign closes it and revokes the entitlements it created.

