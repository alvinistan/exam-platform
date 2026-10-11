# ADR-014: Observability

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Requests cross several services; failures must be traceable without exposing personal data.

## Decision

Structured JSON logs with correlationId (set by the gateway, propagated by every service), Spring Boot Actuator health/readiness, Micrometer metrics and OpenTelemetry traces. Logs ship to CloudWatch; alerts on error rate, latency and failed health checks. Tokens, answers and personal data are never logged.

## Consequences

One trace id follows a request through gateway, assessment, catalog and subscription.

## Alternatives considered

Unstructured logs (rejected).
