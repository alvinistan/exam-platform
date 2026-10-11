# ADR-004: User authentication: Google OIDC with platform-issued tokens

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

Sign-in is Google only (D6, proposed). Services must authorise every request themselves, web and mobile alike.

## Decision

identity-service runs the OIDC authorization-code flow with PKCE and nonce, verifies Google's ID token, then issues its own ES256 JWT access token (15 min; claims sub, roles, status) and a rotating refresh token (30 days, hash stored, family revocation on reuse). Web: refresh token in a __Host- cookie (Secure, HttpOnly, SameSite=Strict), access token in memory only. Mobile: refresh token in Keychain/Keystore. Every service validates JWTs against identity's JWKS; the gateway validates too but is not the only check.

## Consequences

Suspension takes effect within one access-token lifetime (15 min) and immediately for refresh. Key rotation needs JWKS overlap. Adding email/password later is an identity-service-only change.

## Alternatives considered

Google ID tokens used directly by services (rejected: couples every service to Google, no platform roles); server sessions (rejected: awkward for mobile and multiple services).
