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

## Sign-in (Phase 5)

Sign-in is Google only. Create a **Web application** OAuth client in Google Cloud Console (Google Auth Platform →
Clients) with these authorized redirect URIs, then put the client ID and secret in `.env`:

- `http://localhost:8088/api/auth/google/callback` (Docker Compose stack)
- `http://localhost:5173/api/auth/google/callback` (`npm run dev`; also set `PUBLIC_BASE_URL=http://localhost:5173`)

`ADMIN_BOOTSTRAP_EMAIL` makes that Google account the first administrator on its first sign-in; it only ever
happens once. Further administrators are added from **Administration → Users**.

How it works (ADR-004): identity-service runs the Google flow with PKCE, nonce and a browser-bound state, then
issues its own 15-minute ES256 access token (kept in memory by the web app) and a 30-day rotating refresh token in a
`__Host-rt` HttpOnly cookie. Every service verifies access tokens against `/.well-known/jwks.json`.

### Signing keys

Locally, identity-service generates a temporary signing key at startup (tokens stop working after a restart; the
web app just refreshes). Staging and production must set a persistent key and `IDENTITY_REQUIRE_SIGNING_KEY=true`:

```bash
docker compose -f infra/compose/docker-compose.yml --env-file .env run --rm --no-deps identity-service --generate-signing-key
```

Store the printed JSON as `IDENTITY_JWT_SIGNING_KEYS` in the secret store. To rotate: put the new key first and keep
the old one in the set for at least 15 minutes, then remove it.

## Conventions

- **Errors** are RFC 9457 problem responses with a stable `code` (see `contracts/openapi/common.yaml`).
- **Correlation IDs**: every response carries `X-Correlation-Id`; it appears in every log line.
- **Security**: every service validates the access token itself and denies by default; each service opens only
  its own public endpoints. `/api/admin/**` needs the ADMIN role, `/internal/**` a service token (never routed by
  the gateway). The gateway also rejects invalid tokens early and rate-limits sign-in, checkout and attempt starts.
- **Data ownership**: one database and one login per service; no cross-service foreign keys or queries (ADR-003).
- **Secrets** live only in `.env` (git-ignored) or AWS Secrets Manager, never in the repository.
- **Branches**: each phase is developed on its own branch and merged through a pull request after CI passes.

## Status

Phases 0–4 are complete. Phase 5 adds Google sign-in, roles, account management and user administration.
See `CHANGELOG.md`.
