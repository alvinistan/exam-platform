# ADR-002: Technology versions

- **Status:** Accepted (Phase 3, 11 Oct 2026)
- **Deciders:** Product owner (client), development lead

## Context

The master specification fixes the stack but not versions. Spring Boot 3.5 reached end of OSS support in June 2026.

## Decision

Java 21 LTS; Spring Boot 4.1.x; the Spring Cloud release train compatible with Boot 4.1; PostgreSQL 16; Flyway; React 19, Vite 8, Tailwind 4, React Router 8, TanStack Query 5, React Hook Form 7, Zod 4, TypeScript 5.x; Vitest, Playwright; Flutter stable (Release 2). Exact patches pinned in Phase 4 after a verified build.

## Consequences

Newest lines may have early library gaps; fallback is the previous minor (Boot 4.0.x) if a blocker appears.

## Alternatives considered

Boot 3.5 (end-of-life); TypeScript 7.0 (too new for the toolchain today).
