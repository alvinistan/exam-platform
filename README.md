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
| (not published) | PostgreSQL. To connect a database tool, add `-f infra/compose/docker-compose.db-port.yml` (port 15432) |

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

## Setting up an exam (Phases 6–7.5)

Administrators build exams from **Administration → Exams** in the web app, without code changes. Each exam is set up
in five guided steps (`/admin/exams/<id>/<step>`):

1. **Details**: title, address (`/exams/<slug>`), category (add one on the spot), descriptions.
2. **Subjects & topics**: Subject → Module → Topic, built in place. A subject can also be shared with another exam.
3. **Questions**: write or import them, then mark them ready. Only ready questions reach students.
4. **Modes**: turn on Practice, Mock exam and/or Guided practice and set their question counts (a mock exam needs a
   time limit).
5. **Review & publish**: a checklist where every problem links to the step that fixes it; publish when it is ready.

**Create sample exam (O/L Science)** on the Exams page builds a complete draft exam with 24 ready questions, to see a
finished setup or try it as a student. Renaming an exam's address keeps the old one redirecting. Archived exams leave
the catalogue and their page says "unavailable"; **Restore to draft** brings them (and their address) back.

Questions are written in **Administration → Questions** (Phase 7). For a quick local demo you can still add clearly
labelled placeholder questions to a topic (local development only):

```bash
docker compose -f infra/compose/docker-compose.yml --env-file .env exec -T postgres \
  psql -U postgres -d catalog -v topic_title="Dosage by weight" -v how_many=10 < infra/dev-data/demo-questions.sql
```

## Question bank (Phase 7)

- **Write**: from the exam's step 3, or Administration → Question bank → New question. Rich text (bold, italic, lists), 4–5 options, single or
  multiple correct answers, images with alt text, explanation, key points, definitions, difficulty, solving time,
  tags, Trial or Main bank. The preview shows the question as a student sees it, answering and in review.
- **Workflow**: Draft → (Reviewed, optional) → Ready (the API calls it PUBLISHED) → Archived. A question can only
  be reviewed or marked ready when it is complete (FR-QB-04). Marking it ready freezes that version; editing it later
  creates a new version, and students keep the ready one until the change is made live.
- **Import**: from the exam's step 3, or Question bank → Import. Download the CSV or Excel template, fill one row per question, upload, check the
  row-by-row report, then confirm. Imported questions start as drafts.
- **Bulk actions**: select questions in the list to change status, move topic or change bank (all or nothing).
- **Images** are stored on the `catalogmedia` volume and shown through links that expire after about 10 minutes. Set
  `CATALOG_MEDIA_SIGNING_KEY` (`openssl rand -base64 32`) so links survive restarts; staging and production must.

## Taking exams (Phase 9)

Each exam offers up to three exam types: **Practice**, **Mock exam** and **Adaptive exam**. A student opens the exam,
presses Start on a type, picks topics and either a number of questions or the minutes they have.

- assessment-service owns attempts. It fetches the question pool from catalog-service, chooses the questions
  (topics take turns, no repeats, questions seen in the last 7 days last), copies the answer key, and marks every
  answer itself. Practice and Adaptive check each answer as it is given; a Mock exam shows answers after submitting
  (or after each question, if set in step 4).
- Mock exams are timed by the server (ADR-010): answers more than 5 s late are refused, and a sweep every 15 s
  finishes papers whose time ran out. Untouched practice sessions are closed after 24 h.
- **Adaptive difficulty (ADR-016):** each student has a level per topic (Easy, Medium, Hard), worked out from their
  marked answers: up after 80%+ correct, down below 40%, judged on at least 5 answers since the last change. All three
  exam types choose difficulty from it; an Adaptive exam chooses each next question after the previous answer and gives
  weak topics more questions. Students see it under **My progress** (`/progress`).
- assessment-service signs in with the client-credentials grant: set `ASSESSMENT_SERVICE_CLIENT_SECRET` in `.env`
  (any long random value; identity-service and assessment-service both read it). Docker Compose refuses to start
  without it.

## Free and Premium access

subscription-service decides access on every request (ADR-007); nothing is cached past a change or an expiry.

- **Free attempts per exam type**: each exam type of each exam gives every student a number of free attempts
  (set in the exam's setup, step 4; defaults Practice 3, Mock 1, Adaptive 3). When they are used up that exam type
  needs Premium; past results stay. **Administration → Access** lists the numbers for every published exam.
- **Premium access by hand**: Access → Premium access for students.
- **Paid plans**: see Payments below.
- subscription-service learns each exam's free attempts from catalog-service's event feed, a few seconds after a
  change. It signs in with `SUBSCRIPTION_SERVICE_CLIENT_SECRET` from `.env`.

## Payments (Phase 11)

Students buy plans on `/plans`; administrators set plans and see payments in **Administration → Plans & payments**.
A plan covers one exam, a bundle of exams, or everything; lasts 1, 3, 6 or 12 months; has an LKR and/or USD price;
and sets Practice, Mock and Adaptive attempts (unlimited, a number per exam, or not included). Changes apply to new
payments only.

**Promotions** (Phase 11C): a plan can have a sale price between two dates (edit the plan). **Administration →
Promotions** has discount codes (percentage or fixed amount, limits per student and overall; a code that covers the
whole price starts the plan without payment) and free access campaigns (for every student between two dates, or for
chosen students for 1–12 months).

- **Out of the box** `PAYMENT_PROVIDER=TEST`: choosing a plan opens a clearly labelled test page where no money moves.
  It is refused when the `prod` profile is active.
- **PayHere sandbox**: create a sandbox account at sandbox.payhere.lk, add your domain under Settings → Domains &
  Credentials, then set in `.env`:
  `PAYMENT_PROVIDER=PAYHERE`, `PAYHERE_MERCHANT_ID`, `PAYHERE_MERCHANT_SECRET` (the secret for that domain),
  `PAYHERE_SANDBOX=true` and `PUBLIC_BASE_URL`.
- **PayHere must reach the server**: it confirms payments by posting to `PUBLIC_BASE_URL/api/payments/webhooks/payhere`,
  so `http://localhost` won't work. For local testing use a tunnel (for example `cloudflared tunnel --url
  http://localhost:8088`) and set `PUBLIC_BASE_URL` to the tunnel's https address. Premium starts only when that
  confirmation arrives; the return page waits for it.
- **Live**: `PAYHERE_SANDBOX=false`, live merchant credentials and an https `PUBLIC_BASE_URL`; the service refuses to
  start otherwise.

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

Phases 0–11 are complete: catalogue, question bank, guided exam setup, free attempts and Premium access, the exam
flow for Practice, Mock and Adaptive exams, adaptive difficulty per topic with a "My progress" page, the student
dashboard, and Premium plans with PayHere payments. QA and security hardening come next; AI explanations are
deferred. See `CHANGELOG.md`.