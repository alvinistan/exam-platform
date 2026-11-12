# End-to-end, accessibility and load tests

Phase 14. These run against the **whole stack in Docker**: the production web server (Caddy, with its security
headers), the gateway, all five services and PostgreSQL. Google sign-in is replaced by a small fake
(`fake-google/server.mjs`) that only `infra/compose/docker-compose.e2e.yml` starts. Never deploy that file: anyone who
can reach the fake can sign in as any email address.

CI runs all of this on every pull request (job "End-to-end, accessibility and load tests"); the report, traces,
videos of failures, the load-test summary and the service logs are kept as the `e2e-report` artifact for 14 days.

## Run locally

From the repository root, with Docker running and a `.env` made from `.env.example`:

```bash
docker compose -f infra/compose/docker-compose.yml -f infra/compose/docker-compose.e2e.yml --env-file .env up -d --build --wait
cd e2e
npm ci
npx playwright install chromium
npx playwright test            # all journeys; add --ui to watch them
npx playwright show-report     # after a failure: steps, screenshots, trace
```

The `setup` project runs first: the admin (`admin@e2e.test`, set as `ADMIN_BOOTSTRAP_EMAIL` by the e2e compose file)
creates and publishes the sample exam through the admin screens. The other tests use that exam, and every test signs
in a new student, so they don't affect each other. Start from an empty database (`down -v`) for a clean run.

| File | What it checks |
| --- | --- |
| `00-admin-publishes-exam` | Admin creates the sample exam and publishes it; its address works for anyone |
| `01-practice` | Practice and Adaptive: instant feedback, result by topic, history |
| `02-mock-and-premium` | Mock exam: timer, no answers shown before submitting, review screen, unanswered count, reload safety, the free attempt running out (UI and API), buying a plan with the TEST provider, plan unlocking the mock, receipt, admin payments list |
| `03-access-and-headers` | Students can't reach admin pages or APIs; signed-out callers get 401; one student can't read or change another's attempt; production security headers; no CSP violations |
| `04-accessibility` | axe-core WCAG 2.2 A/AA checks on public, student and admin screens; serious and critical problems fail |

## Load test

`load/mock-exam.js` (k6) signs in students through the same sign-in flow, gives each one a plan, then has them take
mock exams over and over: start, answer every question a couple of seconds apart, submit. It fails if more than 1% of
requests fail or if the 95th percentile is over 1.5 s for starting or submitting, or over 0.5 s for saving an answer.

```bash
k6 run -e STUDENTS=20 -e DURATION=3m e2e/load/mock-exam.js
```

Run it after the Playwright setup (it needs the published sample exam). Results are in `docs/testing/test-report.md`.
