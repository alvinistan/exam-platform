# ADR-005: Service-to-service authentication and internal endpoints

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Assessment calls catalog, learning and subscription synchronously; consumers read event feeds.

## Decision

Services obtain short-lived tokens from identity-service with the OAuth2 client-credentials grant (scopes such as catalog.internal, billing.access). Internal endpoints live under /internal/**, require those scopes, and are never routed by the gateway. Calls use 2 s timeouts, one retry for idempotent reads, and a circuit breaker.

## Consequences

Client secrets per service in the secret store. A compromised user token can never reach /internal.

## Alternatives considered

Network trust only (rejected: no defence in depth); mTLS (deferred until Kubernetes, where a service mesh makes it cheap).

## Implementation note (Phase 8, 25 Oct 2026)

identity-service serves `POST /oauth2/token` (client_credentials only; HTTP Basic or form credentials). Clients are
listed under `identity.service-clients` with their scopes; a client without a secret is switched off. Tokens last
5 minutes. subscription-service is the first client (scope catalog.internal); assessment-service is configured for
catalog.internal and billing.access ahead of Phase 9. The 2 s timeouts apply; the retry and circuit breaker arrive
with the first synchronous call on a student's path (Phase 9).
