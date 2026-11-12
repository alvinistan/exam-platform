# Test report — Phase 14

Results from GitHub Actions on the Phase 14 pull request (12 Nov 2026). Every pull request runs all of these again;
the numbers below are from the run that passed.

## What runs on every pull request

| Suite | Where | Count | Result |
| --- | --- | --- | --- |
| Backend unit and integration tests (real PostgreSQL 16 via Testcontainers) | `backend/**/src/test` | 326 | ✅ all pass |
| — of which endpoint protection tests (every endpoint × no token, forged token, wrong role) | `EndpointSecurityTests` | 4 services | ✅ |
| Web unit and component tests (Vitest, React Testing Library) | `apps/web/src/**/*.test.tsx` | 152 | ✅ all pass |
| Database migrations and schema rules | `backend/db-tests` | 89 checks | ✅ |
| OpenAPI contract lint | `contracts/openapi` | every service | ✅ |
| Dependency, secret and image scans (Trivy) | CI | 7 images | ✅ no fixable CRITICAL |
| **End-to-end journeys** (Playwright, whole stack in Docker) | `e2e/tests` | 8 | ✅ |
| **Accessibility** (axe-core, WCAG 2.2 A/AA) | `e2e/tests/04-accessibility.spec.ts` | 18 screens | ✅ no serious or critical problems |
| **Load test** of mock exams (k6) | `e2e/load/mock-exam.js` | 3 minutes | ✅ within limits |

## Load test: mock exams

20 students at once, each taking mock exams back to back for 3 minutes: start a 10-question mock, answer each
question 1–3 seconds apart (far faster than a real student, so heavier than real use), submit. Every service, the
gateway, Caddy and PostgreSQL ran together on one standard GitHub-hosted runner (2 CPUs and 7 GB for a private
repository), a smaller machine than the planned production server.

| Measure | Result | Limit |
| --- | --- | --- |
| Mock exams completed | 178 | — |
| Requests | 2,261 (10 a second) | — |
| Failed requests | 0% | < 1% |
| Checks passed | 2,136 of 2,136 | > 99% |
| Start a mock exam (picks questions, asks for access) | 95% under 727 ms (slowest 1.04 s) | 95% under 1.5 s |
| Save an answer | 95% under 27 ms (slowest 60 ms) | 95% under 0.5 s |
| Submit and mark | 95% under 35 ms (slowest 75 ms) | 95% under 1.5 s |

An earlier run gave nearly the same figures (start 502 ms, answer 22 ms, submit 25 ms at the 95th percentile).
Starting an exam is the slowest step because it calls catalog-service for the question pool and subscription-service
for the access decision. At this rate one server handles a class of students starting together comfortably. Before a
large launch (hundreds of students starting in the same minute), repeat the test on the production server with more
students: `k6 run -e STUDENTS=200 -e DURATION=10m e2e/load/mock-exam.js`.

## Problems the new tests found (all fixed in Phase 14)

| Found by | Problem | Fix |
| --- | --- | --- |
| Security review | The web server's Content Security Policy blocked the PayHere checkout form, so live payments couldn't start | CSP allows PayHere's checkout addresses; e2e checks the header |
| Security review | Formulas lost their layout under the same policy (KaTeX uses inline styles) | `style-src-attr 'unsafe-inline'` (style attributes only; scripts stay locked down) |
| Security review | A production server would accept the PayHere sandbox, where test cards buy real access | Refused under `prod` unless explicitly allowed for staging |
| Security review | Signing keys fell back to temporary ones in production unless a separate flag was set | Required under `prod` |
| Security review | Token refresh shared the sign-in rate limit (20 a minute per connection), so a class on one connection would get errors | Separate allowance (ADR-015 amendment) |
| Security review | A sign-in "return to" address with a space broke the callback after a successful Google sign-in | Only valid same-site paths are accepted |
| New CI check | The Tomcat security override (11.0.25) had become older than Spring Boot 4.1.2's own 11.0.27 | Overrides removed; CI now fails on any downgrade |
| axe | Account menu button: accessible name didn't contain its visible text | Name built from the visible text |
| axe | "Administration" sidebar label and error references: too little contrast | Darker text |
| axe | Links inside sentences were distinguished by colour only | In-text links are underlined |
| axe | Result page: the score line was an invalid child of a description list | Moved out of the list |
| End-to-end | Start page said "Start a adaptive exam" | "Start an adaptive exam" |
| End-to-end | Building all images at once (`docker compose up --build`) sometimes failed: the builds corrupted their shared Maven cache | The cache is locked while in use |

## Not covered automatically

- Manual keyboard and screen-reader pass of the exam screens (NVDA or VoiceOver), especially the mock exam timer
  announcements and the question map.
- Phones: the journeys run on a desktop-sized browser; the screens were checked at phone width by hand during each
  phase.
- Live PayHere payments and refunds (needs the live merchant account; the sandbox flow was tested in Phase 11).
- An independent penetration test before taking payments at scale.

## Running the tests yourself

See `e2e/README.md` for the browser and load tests, and the README for backend and web tests.
