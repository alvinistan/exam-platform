# ADR-015: Rate limiting at the gateway without Redis (for now)

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Sign-in, checkout and attempt creation need rate limits; Redis is only allowed when justified.

## Decision

The gateway applies in-memory token-bucket limits per user and per IP on sensitive routes. This is correct while there is one gateway instance.

## Consequences

When a second gateway instance is added, limits move to Redis (the justified use case).

## Alternatives considered

Redis now (rejected: not needed with one instance).
