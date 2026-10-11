# ADR-006: Domain events through a transactional outbox exposed as a pull feed

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Three event flows exist at launch: AttemptCompleted (assessment→learning), Exam* (catalog→subscription, learning), Subscription* (subscription→learning analytics). The spec says not to add a broker without justification.

## Decision

Each producer writes events to its outbox_event table in the same transaction as the state change, and serves GET /internal/events?after=<seq>. Consumers poll every 2 s, process idempotently (processed_event table), then advance their cursor. The feed only returns rows older than 5 s and producers cap transactions at 5 s, so late-committing rows are never skipped. Delivery is at-least-once.

## Consequences

No broker to run; replay is trivial (reset a cursor). Latency is a few seconds (US-35 allows 10 s). Move to RabbitMQ when there are more than ~5 consumers per event, sub-second needs, or more than one instance per producer.

## Alternatives considered

RabbitMQ or Kafka now (rejected: operational cost not justified yet); direct HTTP push (rejected: producer must know and retry every consumer).
