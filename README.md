# Leenex Labs exam platform

One configurable platform for many multiple-choice exams: practice, timed mock exams and adaptive guided practice,
with a freemium access model. An exam is data an administrator creates, not a page or a service a developer builds.

## Repository layout

```
apps/web/                 React 19 + TypeScript + Vite web app (students and administrators)
backend/
  pom.xml                 Maven parent: Spring Boot 4.1.1, Spring Cloud 2025.1.3, Java 21
  libs/platform-web/      shared error model, correlation IDs, UUIDv7, security baseline (no entities)
  gateway/                Spring Cloud Gateway: the only public entry point for /api
  services/
    identity-service/     users, roles, Google sign-in              → database: identity
    catalog-service/      exams, topics, trial + main banks         → database: catalog
    assessment-service/   attempts, timing, scoring                 → database: assessment
    learning-service/     mastery, misconceptions, recommendations  → database: learning
    subscription-service/ free policy, plans, payments, access      → database: billing
  db-tests/               schema rule checks run against real PostgreSQL
contracts/openapi/        one OpenAPI 3.1 contract per service (+ common.yaml)
infra/
  docker/                 service image (one Dockerfile, MODULE build argument)
  compose/                local stack: PostgreSQL, services, gateway, web
  caddy/                  web server config: static app, /api proxy, security headers
docs/adr/                 architecture decision records
.github/workflows/        CI: build, tests, contract lint, schema checks, scans, images
```

The AI learning service (Release 2) and the Flutter mobile apps are not part of Release 1 and have no code yet.

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Docker Desktop | 4.x with Compose v2 | Running the full stack (only requirement for that) |
| JDK | 21 (Temurin recommended) | Building and testing the backend without Docker |
| Maven | 3.9+ | Same |
| Node.js | 24 LTS (or 22.22.2+) | Web app development |

## Run the full stack with Docker

```bash
cp .env.example .env            # then replace every change-me value
docker compose -f infra/compose/docker-compose.yml --env-file .env up --build
```

On Windows PowerShell use `Copy-Item .env.example .env` for the first line.

| URL | What |
| --- | --- |
| http://localhost:8088 | Web app (served by Caddy, proxies `/api` to the gateway) |
| http://localhost:8080/actuator/health | Gateway health |
| localhost:5432 | PostgreSQL (bound to this machine only) |

The first build downloads Maven and npm dependencies and takes several minutes; later builds use the cache.
To reset all data: `docker compose -f infra/compose/docker-compose.yml down -v`.

## Develop without Docker

```bash
# Backend: unit and integration tests (Testcontainers needs Docker running)
cd backend && mvn verify

# One service against your own PostgreSQL
cd backend && mvn -pl services/catalog-service -am spring-boot:run \
  -Dspring-boot.run.arguments="--DB_URL=jdbc:postgresql://localhost:5432/catalog --DB_USERNAME=catalog_svc --DB_PASSWORD=..."

# Web app (proxies /api to http://localhost:8080)
cd apps/web && npm ci && npm run dev      # http://localhost:5173
npm run lint && npm run typecheck && npm test && npm run build

# API contracts
cd contracts/openapi && npx @redocly/cli@2.62.1 lint

# Schema rules against an empty PostgreSQL 16 (as a superuser)
PGHOST=localhost PGUSER=postgres PGPASSWORD=... backend/db-tests/run.sh
```

## Conventions

- **Errors** are RFC 9457 problem responses with a stable `code` (see `contracts/openapi/common.yaml`).
- **Correlation IDs**: every response carries `X-Correlation-Id`; it appears in every log line.
- **Security baseline**: every service denies all API calls by default until token validation lands (Phase 5);
  only health probes and the OpenAPI document are public. `/internal/**` is never routed by the gateway.
- **Data ownership**: one database and one login per service; no cross-service foreign keys or queries (ADR-003).
- **Secrets** live only in `.env` (git-ignored) or AWS Secrets Manager, never in the repository.
- **Branches**: each phase is developed on its own branch and merged through a pull request after CI passes.

## Status

Phases 0–3 (assessment, requirements, UI/UX design, architecture) are complete. Phase 4 delivers this foundation.
Feature work starts in Phase 5 (Google sign-in and roles). See `CHANGELOG.md`.
