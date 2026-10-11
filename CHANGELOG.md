# Changelog

## Phase 7 — Question bank and content management

- catalog-service: questions in the Trial and Main banks with rich text (allow-list sanitised), 4–5 options, single
  or multi answer, images, explanation, key points, definitions, difficulty, solving time, marks and tags.
- Workflow Draft → Reviewed → Published → Archived with FR-QB-04 checks; publishing freezes a version, later edits
  create a new version while the published one stays live; discard unpublished changes; history of versions.
- Leaving Published is refused when a published exam would no longer have enough questions.
- Search and filters (text, tag, exam, subject, module, topic, status, difficulty, bank), sorting, 25/50/100 per page.
- Bulk actions (change status, move topic, change bank by copy-and-archive), all or nothing, audited.
- Misconception categories linked to wrong options.
- Images: byte-sniffed, size- and dimension-limited, re-encoded, stored on a volume, served through signed links.
- CSV/XLSX imports with templates, a per-row report and confirmation; nothing is saved until confirmed.
- Migration V2: `question_import`, tag and recency indexes.
- identity-service: `GET /api/admin/users/names` for "created by / changed by" names (FR-QB-12).
- gateway: routes `/api/media/**`; forwards uploads untouched (multipart parsing off).
- web: question list with bulk actions, editor (TipTap rich text) with live student preview, workflow panel, version
  history, import wizard; reusable `QuestionView` for the attempt screens to come.
- CI: each test times out after 3 minutes instead of hanging the build.

## Phase 6 — Configurable exam catalogue

- catalog-service: categories (create, edit, archive/restore; archive refused while exams are published); content
  library of subjects → modules → topics shared across exams, with archive and topic prerequisites (no loops);
  exams with slug, descriptions, instructions, SEO fields, catalogue position, featured flag and linked subjects;
  per-mode settings with cross-field validation (mock exams need a time limit and after-submit feedback).
- Publishing rules (FR-CAT-05): refused with a `blockers` list until there is an enabled mode, an active topic, enough
  published Main-bank questions for every enabled mode's minimum and a free catalogue position. Changes that would
  break a published exam (disabling its last mode, archiving its topics, unlinking subjects) are refused too.
- Slugs: renamed addresses answer 301 to the new one and can never be reused by another exam (FR-CAT-06).
- Public API: `/api/categories`, `/api/exams` (search, category, featured, paging) and `/api/exams/by-slug/{slug}`
  with the content tree and public mode settings; never question content. Draft or archived exams answer
  `EXAM_UNAVAILABLE`.
- Optimistic locking (If-Match/ETag) on every edit; audit rows for every change; `ExamPublished`, `ExamUpdated` and
  `ExamArchived` events in the outbox, readable by services at `/internal/events`.
- platform-web: problem responses can carry extra members (`blockers`); new code `EXAM_UNAVAILABLE`.
- web: catalogue page (search, category filter, paging in the URL), exam page (modes, instructions, what's covered,
  unavailable and moved addresses), featured exams and categories on the home page, administration screens for
  exams, categories and the content library.
- `infra/dev-data/demo-questions.sql` adds labelled demo questions for local publishing (development only).

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
