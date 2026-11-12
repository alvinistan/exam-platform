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

## Amendment (Phase 14, 12 Nov 2026)

Token refresh (`POST /api/auth/token`, `/api/auth/logout`) shared the sign-in limit of 20 a minute per address. The web
app refreshes on every page load and every 15 minutes, so a class sharing one internet connection would have been
refused. Limits are now:

| Rule | Paths | Key | Burst / per minute |
| --- | --- | --- | --- |
| sign-in | `/api/auth/google/**` | address | 60 / 60 |
| token-refresh | `POST /api/auth/token`, `/api/auth/logout` | address | 300 / 300 |
| checkout | `POST /api/subscriptions/checkout` | user | 5 / 5 |
| price-quote | `POST /api/subscriptions/quote` | user | 10 / 10 |
| attempt-start | `POST /api/attempts` | user | 20 / 20 |

The address is the one Caddy reports in `X-Forwarded-For` (Caddy replaces any value sent by the client; the gateway is
only reachable through Caddy). If a load balancer is ever put in front of Caddy, configure Caddy's `trusted_proxies`
for it, or every student will share the balancer's address.
