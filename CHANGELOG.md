# Changelog

## Phase 8 — Freemium access and entitlements

- identity-service: OAuth 2.0 client-credentials grant at `POST /oauth2/token` for backend services (ADR-005). Clients
  and scopes are configured per service; secrets come from the environment; tokens carry role SERVICE, last 5 minutes
  and never act for a user. Not routed by the gateway.
- subscription-service: reads catalog-service's event feed into `exam_view` (cursor, processed-event de-duplication);
  free-access rules FA-1 (first N by catalogue position, global or per category), FA-2 (welcome attempts, off by
  default), FA-3 (Trial bank for free and welcome access), FA-4 (modes on free exams), FA-5 (an exam started while free
  stays free; welcome attempts counted once per attempt, serialised per student), FA-7 (an active entitlement unlocks
  every premium exam). Entitlements are checked on every request.
- APIs: `GET /api/access/exams` (public), `GET /api/access/me`, `GET/PUT /api/admin/billing/policies`,
  `GET/POST /api/admin/billing/entitlements`, `POST …/{id}/revoke`, `POST /internal/access-decisions`
  (scope billing.access). Every policy and entitlement change is audited.
- catalog-service: the event feed only returns events at least 5 s old, so late-committing changes are never skipped
  (ADR-006).
- Migration V2 (billing): `free_exam_claim`; manual grant reason; exam title and address in `exam_view`.
- web: Free / Premium badges on catalogue and home cards; locked preview on premium exams (drawn shapes only) with
  sign-in or next steps; premium-only modes marked on free exams; "Your plan" on the account page; Administration →
  Access (rules, published exams with reasons, give and revoke Premium); access shown on the exam setup's last step.
- Docker: `SUBSCRIPTION_SERVICE_CLIENT_SECRET` is required in `.env`.

## Phase 7.5 — Guided exam setup (admin redesign)

- web: an exam is now set up in 5 guided steps on one page — Details, Subjects & topics, Questions, Modes, Review &
  publish — with a step bar that shows what is done and what needs attention, worked out from server counts.
- web: the exams list shows each exam's setup progress and its next step ("Continue setup"); live exams show their
  ready questions and modes; archived exams can be restored from the list.
- web: categories can be added from step 1; subjects, modules and topics are created, renamed, archived and restored
  inside step 2; step 3 marks every draft ready in one click and lists any question that still needs fixing.
- web: every publishing problem links to the step that fixes it. The question editor and import page lead back to
  the exam's setup. Admin navigation is Exams, Question bank, Categories, Users.
- Questions: "Published" is shown as "Ready"; review is optional (Draft → Ready is allowed).
- catalog-service: `POST /exams/{id}/restore` (archived → draft, address kept, `ExamRestored` event);
  `POST /exams/{id}/subjects` (link an existing subject or create one by title) and `DELETE /exams/{id}/subjects/{subjectId}`;
  `draftQuestionCount` and `subjectCount` on admin exam responses; readiness `issues` tagged with the setup step.
- catalog-service: `POST /sample-exams/ol-science` builds a clearly labelled sample exam (G.C.E. O/L Science: 3 modules,
  6 topics, 24 ready questions, Practice and Mock exam on) as a draft.
- Fixed: archiving an exam could not be undone, and its address stayed blocked for good.
- Docker: services get 3 minutes to start before a health check counts, so a first `docker compose up` no longer
  gives up while migrations run.

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
