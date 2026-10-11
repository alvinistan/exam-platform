# ADR-010: Server-authoritative exam timing

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Timers must survive refreshes and network loss and cannot be trusted to the browser.

## Decision

expires_at is set by the server at attempt creation. Answers after expires_at + 5 s are rejected. A scheduler finalises expired attempts every 15 s, and any request touching an expired attempt finalises it first. Clients render the countdown from expiresAt and serverNow. Untimed attempts idle for 24 h are closed as ABANDONED.

## Consequences

A student who loses connection keeps their saved answers and remaining time; clock skew on the device has no effect.

## Alternatives considered

Client-side timer with server check only at submit (rejected: answers could keep flowing after expiry).
