# Changelog

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
  migration and schema-rule checks, Trivy dependency/secret/image scans; Dependabot.

## Phase 3 — Architecture and technical design

- Flyway migrations for the five service databases; 60 schema-rule checks.
- OpenAPI 3.1 contracts for five services (65 operations).
- ADR-001 to ADR-015.
