# Changelog

## Phase 5 — Google sign-in and roles

- identity-service: Google OpenID Connect with PKCE, nonce and browser-bound state; ES256 access tokens (15 min)
  published through JWKS; rotating refresh tokens (30 days) with reuse detection and a 30-second multi-tab grace;
  one-time admin bootstrap; profile with optimistic locking; account deletion (anonymised); admin user list, search,
  grant/revoke admin, suspend/reactivate with reason; every change audited. Migration V2 (pending sign-ins).
- platform-web: every service verifies tokens (signature, issuer, audience, expiry, active account), maps roles,
  requires ADMIN for `/api/admin/**` and a service token for `/internal/**`; method security errors become 403s.
- gateway: rejects invalid tokens early; in-memory rate limits on sign-in, checkout and attempt starts (ADR-015).
- web: in-memory session with silent renewal, account menu, sign-in page, account page, administration Users page;
  signed-in areas lazy-loaded.
- `identity-service --generate-signing-key` prints a production signing key.

## Phase 4 — Development foundation

- Maven multi-module backend on Spring Boot 4.1.1 / Spring Cloud 2025.1.3 / Java 21.
- `platform-web` library: problem responses with stable codes, correlation IDs, UUIDv7 keys, default-deny security.
- Gateway (Spring Cloud Gateway server WebMVC) with one route per service; `/internal/**` is never routed.
- Five services that start, apply their Flyway migration, expose health probes and an OpenAPI document,
  and reject every API call until Phase 5 adds token validation.
- React 19 web app: design tokens from the Phase 2 design system, Figtree (self-hosted), shared components,
  typed API client, dynamic `/exams/:slug` route, loading/empty/error states.
- Docker images (non-root), Docker Compose stack with one database and login per service, Caddy web server
  with security headers.
- GitHub Actions CI: backend tests with Testcontainers, web lint/typecheck/tests/build, OpenAPI lint,
  migration and schema-rule checks, Trivy dependency/secret/image scans; Dependabot. Failures and scan findings
  appear as annotations on the pull request.
- Security overrides from the first image scan: Tomcat 11.0.25, Jackson 3.1.7 / 2.21.7, Caddy 2.11, `apk upgrade`.
- Gateway proxies with Apache HttpClient 5 (the JDK client failed intermittently on proxied requests).

## Phase 3 — Architecture and technical design

- Flyway migrations for the five service databases; 60 schema-rule checks.
- OpenAPI 3.1 contracts for five services (65 operations).
- ADR-001 to ADR-015.
