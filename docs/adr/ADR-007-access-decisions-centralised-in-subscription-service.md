# ADR-007: Access decisions centralised in subscription-service

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Free-access rules (FA-1..FA-7) and entitlements must be enforced identically everywhere and never cached past expiry.

## Decision

subscription-service owns policies, subscriptions and entitlements and answers POST /internal/access-decisions (allowed, bank, reason). assessment-service calls it before creating any attempt and before disclosing premium explanations. It keeps billing.exam_view from catalog events to evaluate catalogue position. Decisions are computed per request; no cross-request cache.

## Consequences

One place to change free rules; a subscription-service outage blocks starting attempts (fail closed) but not finishing or reviewing free content already started.

## Alternatives considered

Each service evaluating rules itself (rejected: drift); JWT claims carrying entitlements (rejected: stale for up to 15 min after expiry or revocation).
