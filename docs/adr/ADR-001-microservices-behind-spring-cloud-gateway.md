# ADR-001: Microservices behind Spring Cloud Gateway

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

The client chose microservices from day one (decision D2, 11 Oct 2026) over a modular monolith.

## Decision

Six independently deployable Spring Boot services — identity, catalog, assessment, learning, subscription, ai-learning (Release 2) — behind one Spring Cloud Gateway. No service per exam: an exam is data. Each service owns its database, API contract and Dockerfile.

## Consequences

More moving parts to run and monitor than a monolith; mitigated by one Docker Compose file, shared build conventions and a single EC2 host at first. Services can scale and deploy independently later.

## Alternatives considered

Modular monolith (recommended in Phase 0, declined by the client).
