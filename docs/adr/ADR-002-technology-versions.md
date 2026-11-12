# ADR-002: Technology versions

- **Status:** Accepted (Phase 3, 11 Oct 2026); versions pinned in Phase 4
- **Deciders:** Product owner (client), development lead

## Context

The master specification fixes the stack but not versions. Spring Boot 3.5 reached end of OSS support in June 2026.

## Decision

Java 21 LTS; Spring Boot 4.1.x; the Spring Cloud release train compatible with Boot 4.1; PostgreSQL 16; Flyway; React 19, Vite 8, Tailwind 4, React Router 8, TanStack Query 5, React Hook Form 7, Zod 4, TypeScript 5.x; Vitest, Playwright; Flutter stable (Release 2). Exact patches pinned in Phase 4 after a verified build.

## Consequences

Newest lines may have early library gaps; fallback is the previous minor (Boot 4.0.x) if a blocker appears.

## Alternatives considered

Boot 3.5 (end-of-life); TypeScript 7.0 (too new for the toolchain today).

## Pinned versions (Phase 4, verified by CI on 11 Oct 2026)

| Area | Version | Notes |
| --- | --- | --- |
| Java | 21 (Temurin) | CI and runtime images |
| Spring Boot | 4.1.2 | Parent POM |
| Spring Cloud | 2025.1.4 (Oakwood) | Supports Boot 4.1.x from 2025.1.2 |
| springdoc-openapi | 3.0.3 | |
| Tomcat / Jackson 3 / Jackson 2 | 11.0.27 / 3.1.7 / 2.21.7 (Boot-managed) | The Phase 4 security overrides were removed in Phase 14: Boot 4.1.2 ships these fixed versions. CI fails if a future override is older than Boot's version |
| Apache HttpClient | 5 (Boot-managed) | Gateway upstream client; the JDK client raced on proxied bodies |
| PostgreSQL | 16 (alpine) | Compose, CI, Testcontainers |
| Node.js | 26 LTS | CI and web image build |
| React / React DOM | 19.3.0 | |
| Vite / plugin-react | 8.3.4 / 6.1.2 | |
| Tailwind CSS | 4.3.3 | |
| React Router | 8.4.0 | |
| TanStack Query | 5.104.1 | |
| TypeScript | 5.9.3 | 7.0 deferred (typescript-eslint supports < 6.1) |
| Vitest / Testing Library | 5.0.3 / 16.3.3 | |
| ESLint / typescript-eslint | 10.12.0 / 8.71.1 | |
| Caddy | 2.11 (alpine) | Web server image |

React Hook Form and Zod are added with the first forms (Phase 5 onwards). Overrides are removed once a Spring Boot
patch release manages an equal or newer version; Dependabot raises those updates weekly.
