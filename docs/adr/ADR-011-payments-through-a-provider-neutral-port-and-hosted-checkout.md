# ADR-011: Payments through a provider-neutral port and hosted checkout

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

The provider and currency are not decided (D8); card data must never touch the platform.

## Decision

subscription-service defines a PaymentProvider port (createCheckout, verifyWebhook). Adapters: Sandbox (labelled, refused by the production profile at startup), then the chosen provider. Activation happens only on a verified webhook (signature over raw body, 5-minute timestamp tolerance), recorded once per provider event id, in one transaction with the subscription and entitlement.

## Consequences

PCI scope stays with the provider (SAQ-A). Switching providers is an adapter change.

## Alternatives considered

Embedding card fields (rejected: PCI scope); activating on the redirect back (rejected: forgeable).
