# Changelog

## Phase 14 — QA and security hardening

- **Security review** of every service, the gateway, the web app and the Docker set-up; results and the go-live list
  are in `docs/security/security-checklist.md`. Fixes:
  - The checkout page couldn't send students to PayHere: the web server's Content Security Policy blocked the form.
    Formulas also lost their layout under the same policy. Both are allowed now (and tested).
  - A production server refuses to start with the PayHere **sandbox** (sandbox test cards would have bought real
    access) unless `PAYMENTS_ALLOW_SANDBOX_IN_PROD=true` is set for a staging site.
  - Under the `prod` profile the services refuse to start without their signing keys (access tokens, image links).
  - **Rate limits:** refreshing the sign-in (every page load) had the same limit as signing in, 20 a minute per
    internet connection, so a class on one connection would have seen errors. Refresh now has its own allowance
    (300 a minute); signing in allows 60 a minute.
  - The address to return to after sign-in accepts only plain paths on the site.
- **Endpoint protection tests:** every endpoint of every service is called without a token, with a forged token and
  with the wrong role; only endpoints listed as public may answer.
- **End-to-end tests** (`e2e/`, Playwright) against the whole stack in Docker, on every pull request: the admin
  publishes an exam; Practice, Adaptive and Mock exams; free attempts running out; buying a plan; receipts; access
  between students and roles; security headers. A small fake Google sign-in is used for the tests only.
- **Accessibility:** automated WCAG 2.2 A/AA checks (axe) on public, student and admin screens.
- **Load test** of mock exams (k6) in CI, with limits on errors and response times; results in
  `docs/testing/test-report.md`.
- **Updates:** Spring Boot 4.1.2, Spring Cloud 2025.1.4, Node.js 26, image and HTML-sanitiser libraries, setup-java
  (replaces Dependabot pull requests #22–#26). Spring Boot now ships the fixed Tomcat and Jackson versions, so the
  Phase 4 overrides are removed; CI fails if an override would ever downgrade a library.
- The start page says "Start an adaptive exam".

## Phase 13 — Finance reports and refunds

- **Finance** (Administration → Finance), for a period you choose (last 7 or 30 days, this or last month, this year,
  or custom), in your own time zone:
  - Paid, refunded and net amounts per currency, and discounts given.
  - Payments by what happened to them, and a chart of paid per day (or month).
  - Subscribers: with a paid plan now, ending within 7 or 30 days, new and returning buyers, free access.
  - Students whose plan ends soon, with their email, and totals by plan.
- **Payments & refunds** (Administration → Payments & refunds): every payment, newest first, filtered by dates,
  status (paid, refunded, free, waiting, failed, cancelled), currency, plan, discount code or payment id, with the
  student's name and email. **Download CSV** (up to 5,000 rows, with names, emails, list price, discount, paid,
  refunded, net), protected against spreadsheet formula injection.
- **Refunds**: open a payment and refund all or part of it, with a reason (students don't see it), choosing whether
  the plan stops straight away. A confirmation step shows exactly what happens.
  - With a PayHere API key (`PAYHERE_APP_ID`, `PAYHERE_APP_SECRET`) the refund goes through PayHere's Refund API;
    otherwise refund in PayHere's portal and record it.
  - A refund PayHere never answered stays "waiting" and still counts against the payment until the administrator
    records whether it went through.
  - Refunds can never add up to more than the payment (checked by the server and by the database). Everything is
    audited.
- **Students**: Account → All payments and receipts lists every payment with refunds; each paid one has a printable
  receipt (list price, sale, discount code, paid, refunds, net, PayHere payment id). Print or save as PDF.
- **Backend**:
  - subscription-service migration V7 (`refund` table with a trigger keeping refunds within the payment, report
    indexes).
  - New endpoints: `GET /api/admin/billing/payments` (now filtered and paged), `/payments/{id}`,
    `POST /payments/{id}/refunds`, `POST /refunds/{id}/resolve`, `GET /finance/summary`, `GET /api/subscriptions/payments`.
  - identity-service: `GET /api/admin/users/contacts` (name and email for finance; `/names` stays without emails).
  - ADR-011 amended.
- The admin "Plans & payments" page is now "Plans"; payments moved to their own page.

## Phase 12 — Mathematical and chemical formulas in questions

- **Formulas everywhere a question is written**: the question, every option, the explanation, key points and
  definitions. Write LaTeX between `\(` and `\)` (in the line) or `\[` and `\]` (on its own line); chemistry with
  `\ce{…}`.
- **Formula button** in the question editor: a LaTeX box with buttons for fractions, roots, powers, subscripts,
  symbols, sums, integrals, limits and chemistry, a live preview, and "on its own line". Selecting a formula and
  pressing the button edits it.
- **Typeset for students** with KaTeX in Practice, Mock, Adaptive, results review and the admin preview. It is loaded
  only when a question has a formula, and readable by screen readers (MathML).
- **Checks**: the editor won't save a formula KaTeX can't read, or one using links or HTML. The server refuses
  formulas that aren't closed, are empty, or are longer than 1000 characters (also for imports).
- ADR-017; catalogue contract and import instructions updated.

## Phase 11C — Promotions: sale prices, discount codes, free access campaigns

- **Sale prices**: any plan can be cheaper in LKR and/or USD between two dates. Pricing shows the regular price crossed
  out, a "Sale" badge and when it ends; checkout charges the sale price while it's on.
- **Discount codes**: a percentage or a fixed amount (per currency), for every plan or chosen plans, between two dates,
  with a total limit and a per-student limit. Pending and paid payments count as uses; failed ones give the use back.
  Students apply a code at checkout and see the discount before paying. The server works out the price every time.
- **Free with a code**: when a code covers the whole price, the plan starts straight away. No PayHere and no address
  form (provider `FREE`).
- **Free access campaigns**: an exam, a bundle or everything, with attempt limits like a plan. Either for every student
  between two dates (shown as a banner on Pricing) or for chosen students for 1, 3, 6 or 12 months. Ending a campaign
  stops its access. It's counted and enforced exactly like a plan.
- **Administration → Promotions**: campaigns (with a student search) and discount codes with their use. The plan dialog
  gains a sale section. Payments show discounts and free activations.
- **Backend**: subscription-service migration V6 (`campaign`, `discount_code`, sale columns, payment discount
  columns); `POST /api/subscriptions/quote` (rate limited), `GET /api/plans/offers`, admin endpoints; buyer details
  are only required for PayHere; contract updated; ADR-011 amended.

## Phase 11B — Dynamic plans: exams, bundles, limits and two currencies

- **Plans for one exam, a bundle of exams, or everything**, each 1, 3, 6 or 12 months, priced in LKR and/or USD.
- **Attempt limits per exam type**: Practice, Mock and Adaptive are each unlimited, a number of attempts (per subject in
  a bundle), or not included. Enforced by the server on every start; each attempt counts once.
- **What a student buys is frozen**: later price, limit or bundle changes don't affect it. New plans start straight
  away and add to existing ones; an unlimited plan is used first, then the limited plan ending soonest, then free
  attempts.
- **Pricing page**: bundles by category, single subjects, and everything; LKR/USD switch; per-month price and best
  value; `?exam=` shows only plans for one exam (linked from locked exams).
- **Checkout** in the chosen currency, showing what the plan covers and its limits.
- **Account page**: each active plan with attempts left per exam type on each subject.
- **Administration → Plans & payments**: create, edit, hide or delete (if unpaid) plans; pick one exam or tick the
  exams of a bundle by category; set both prices and the limits.
- **Backend**: subscription-service migration V5 (`plan_exam`, `entitlement_exam`, `entitlement_use`, plan scope,
  prices and limits); contract updated; ADR-011 amended.

## Phase 11 — Subscriptions and payments (PayHere)

- **Premium plans** (`/plans`, "Pricing" in the header): plans on sale with price, price per month, the best-value plan
  and how much it saves. Locked exams and the account page link here.
- **Checkout** (`/checkout/:planId`): the details PayHere asks for (name and email prefilled), then the browser posts a
  signed form to PayHere. A double click or retry never creates a second payment.
- **Return page** (`/checkout/return`): waits for PayHere's confirmation to reach the server, then shows Premium and its
  end date; failures and cancellations say nothing was charged.
- **Verified activation (ADR-011 amended):** PayHere's notification is checked (md5sig, merchant, amount, currency),
  recorded once, and activates the subscription and Premium in one transaction. Renewals start when current Premium
  ends; charge-backs remove Premium; finished periods expire and abandoned checkouts are closed by a sweep.
- **Test payments:** with no provider configured, a labelled test page stands in for PayHere (no money moves); the
  server refuses it under the `prod` profile.
- **Administration → Plans & payments:** create and edit plans (length, price in LKR or USD, on sale or hidden, order),
  and see every payment with its status and PayHere reference.
- **Account page:** Premium end date, "Add more time", and the last ten payments.
- **Backend:** subscription-service `billing` package, migration V4, new endpoints in the contract; `.env.example` and
  Compose gain `PAYMENT_PROVIDER`, `PAYHERE_MERCHANT_ID`, `PAYHERE_MERCHANT_SECRET`, `PAYHERE_SANDBOX`.

## Phase 10 — Student dashboard

- **Dashboard** (`/dashboard`): signed-in students land here from the home page, and it's in the header next to Exams.
  - Greeting and plan status (Free plan / Premium until …).
  - **Continue where you left off:** every unfinished attempt, with when a mock exam's time runs out.
  - **Totals:** questions answered, correct overall, day streak (consecutive days with an answer, in the student's own time zone) and questions answered this week.
  - **Practise next:** the weakest topics across the student's exams, with a button that starts a session on them (Adaptive exam when offered, otherwise Practice).
  - **Activity:** questions answered on each of the last 14 days, with hover/focus details and a screen-reader table.
  - **Your exams:** accuracy, answers, sessions, topics to revise, and links to the exam and its progress page.
  - **Recent results:** the last five finished attempts, with links to review them.
  - New students see a three-step "Start your first exam" guide instead.
- **Backend:** `GET /api/progress/overview?tz=…` (assessment-service) returns totals, streak, 14-day activity, focus topics across exams and per-exam summaries, all from marked answers.
- **Header:** the brand stays on one line on phones.

## Phase 9 (Step B) — Adaptive difficulty per topic and My progress

- **Levels per topic (ADR-016):** every student starts each topic at Medium. After at least 5 answers since the last change, 80%+ correct (of the last 8) moves the topic up a level and below 40% moves it down, one step at a time.
- **All three exam types use the levels:** level 1 asks mostly Easy questions, level 2 a balanced mix, level 3 mostly Hard. If a difficulty runs out, the nearest one is used.
- **Repeated mistakes:** in Practice and Adaptive exams, questions answered wrong last time come back first (after a day, at most about a quarter of a session). In an Adaptive exam, topics that need work get twice the share. Mock exams never repeat mistakes on purpose.
- **Adaptive exam reacts within the session:** each question is chosen on the server after the previous answer, using the level that already includes it. The planned length is fixed at start; it ends early if a topic runs out of questions.
- **My progress** (`/progress`, in the account menu):
  - per exam: questions answered, overall accuracy, and each topic's level (with an up/down trend), accuracy over the last 8 and all answers, and status (Strong / Developing / Needs work / Just started);
  - a "Practise next" card with a one-click session on the weak topics;
  - recent results as bars that open each attempt.
- **Results** show the student's level on each topic now. Questions in an Adaptive exam say which level they were chosen for.
- **Backend:** `GET /api/progress/exams` and `GET /api/progress/exams/{examId}` (assessment-service, routed by the gateway), migration V3 (`planned_count`, `target_level`), `TopicLevels`, `LearningProfile`, and a reworked `QuestionPicker`.

## Phase 9 (Step A) — Exam flow for Practice, Mock and Adaptive exams

Students can now take exams. Built from the client's O/L Science requirements and the Adaptive Exam System spec.

- **Start an exam:** on the exam page each exam type has a Start button. Students pick topics (checkboxes) and either a number of questions (5/10/20/30, up to the exam type's maximum) or the time they have (10/20/30 minutes).
- **Question choice:** random, topics take turns, no repeats within a session, questions seen in the last 7 days come last. Time-based sessions are sized from each question's estimated solving time.
- **Practice and Adaptive exam:** one question at a time; choosing an answer checks it on the server and shows right/wrong, the correct answer, the explanation and key points. Correct answers move on automatically after 2.5 s; after a wrong answer the student presses Next.
- **Mock exam:** server-kept countdown (the minutes chosen, or the questions' estimated time, never above the exam's limit), question map with answered/flagged states, answers changeable until submit, review-before-submit, automatic hand-in at time-up. Answers more than 5 s late are refused. Feedback after submit by default; admins can switch it to after each question. One mock in progress per exam.
- **Results and review:** score, correct/incorrect/not answered, time, pass mark, per-topic breakdown with "Revise next", and every question with its answer and explanation (filter by incorrect / not answered / correct). History of attempts on the exam page.
- **Free attempts per exam type** replace "the first 6 exams are free": each exam type has its own free attempts per student (defaults Practice 3, Mock 1, Adaptive 3). When used up, that exam type is locked until Premium; results are kept. Admin Access page lists them; the old policy rules and their tables are removed.
- **Admin step 4 "Exam types":** on/off, most questions per session (default 30), free attempts, Mock time limit and Mock feedback timing.
- **Subject → Topics:** the setup no longer shows modules; topics are added straight under a subject (kept in a hidden module for compatibility).
- **Sample exam** rebuilt: O/L Science with the 10 topics, 90 questions (3 Easy, 3 Medium, 3 Hard per topic), all three exam types on.
- "Guided practice" is renamed **Adaptive exam**. Difficulty adaptation and the progress profile come in Step B.
- Backend: assessment-service attempt engine (new), catalog-service `/internal/exams/{id}/question-pool` and `/internal/question-versions`, subscription-service free-attempt counting. New `.env` value `ASSESSMENT_SERVICE_CLIENT_SECRET`.

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
