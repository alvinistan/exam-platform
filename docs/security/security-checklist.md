# Security checklist

Phase 14 review of every service, the gateway, the web app and the Docker set-up (12 Nov 2026). Each line says what
protects it and which automated test proves it. "Before go-live" lists what can only be done on the real servers.

## Results of the review

| Area | Status | How it's protected | Proved by |
| --- | --- | --- | --- |
| Every endpoint needs sign-in unless listed as public | ✅ | Spring Security in every service; the gateway also refuses bad tokens early | `EndpointSecurityTests` in identity, catalog, assessment and subscription: calls **every** endpoint with no token and with a token from another issuer; only the listed public ones may answer |
| Admin endpoints need the ADMIN role | ✅ | `/api/admin/**` rule in every service, plus `@PreAuthorize` | Same tests: every admin endpoint refuses a student token (403); e2e `03-access-and-headers` |
| Internal endpoints | ✅ | `/internal/**` needs a service token (client credentials, scoped); not routed by the gateway or Caddy | Same tests: student and admin tokens get 403; e2e checks `/api/internal/**` is 404 from outside |
| One student can't see or change another's attempt | ✅ | Every attempt query filters by the signed-in student; a wrong owner gets 404 (doesn't reveal the attempt exists) | `AttemptTests`; e2e `03-access-and-headers` (read, answer and submit someone else's attempt) |
| Answer keys | ✅ | Kept server-side; shown only after answering (Practice, Adaptive) or after submitting (Mock) | `AttemptTests`; e2e checks no answer is shown during a mock exam |
| Scoring and timing | ✅ | Marked on the server from the stored answer-key snapshot; mock deadline stored on the server, late answers refused, expired attempts finished by a sweep; submitting twice returns the same result | `AttemptTests`; e2e reload after submitting |
| Premium access | ✅ | Every attempt start asks subscription-service, which counts free attempts and checks plans and their end dates per request (no caching) | `AccessTests`, `PlanAccessTests`; e2e: free mock used → button disabled and API 403 → plan bought → allowed |
| Payments | ✅ | PayHere notifications checked by merchant id and MD5 signature, amount and currency compared with the order, each notification applied once; the TEST provider is refused under the `prod` profile | `PayHerePaymentTests`, `TestProviderPaymentTests` |
| PayHere sandbox in production | ✅ fixed | **New:** the `prod` profile refuses `PAYHERE_SANDBOX=true` (sandbox test cards would have bought real access), unless `PAYMENTS_ALLOW_SANDBOX_IN_PROD=true` for a staging site | `PaymentSettingsCheckTest` |
| Refunds | ✅ | Admin only; never more than the payment (service lock and database trigger); audited | `FinanceTests`, database checks |
| Discount codes | ✅ | Prices worked out on the server; code use limits locked during checkout; price checks rate-limited per user | `PromotionTests` |
| Sign-in | ✅ | Google OpenID Connect with PKCE, state and nonce; `__Host-` cookies (Secure, HttpOnly, SameSite); refresh tokens rotate and reuse is detected; a CSRF header is needed to refresh from the browser | `SignInFlowTests` |
| Redirect after sign-in | ✅ fixed | Only plain paths on this site. **New:** anything that isn't a valid path (for example one with a space, which used to break the callback after a successful Google sign-in) goes to the home page | `SafeReturnToTest` |
| Signing keys in production | ✅ fixed | **New:** the `prod` profile refuses to start without `IDENTITY_JWT_SIGNING_KEYS` and `CATALOG_MEDIA_SIGNING_KEY` (they used to need a separate flag) | Configuration (`application-prod.yml`) |
| Rate limits | ✅ fixed | Gateway token buckets (ADR-015). **New:** token refresh has its own allowance; it shared the sign-in limit of 20 a minute per address, which a class on one connection would have exceeded | `GatewayRoutingTests` |
| Uploads | ✅ | Admin only; 2 MB images, size checked before decoding, at most 4096 × 4096; re-encoded to PNG/JPEG without metadata; served from signed, expiring links with `nosniff` and a sandbox CSP | `MediaTests` |
| Question text (XSS) | ✅ | HTML sanitised on the server with an allow-list (OWASP sanitizer); formulas typeset by KaTeX with `trust: false`; CSP allows only our own scripts | `QuestionBankTests`; e2e checks there are no CSP violations |
| Security headers | ✅ fixed | Caddy sets HSTS, CSP, `nosniff`, Referrer-Policy, Permissions-Policy, COOP and hides `Server`. **New:** CSP allows the PayHere checkout form (it was blocked) and inline style attributes that formulas need | e2e `03-access-and-headers` |
| CSV export | ✅ | Cells starting with `= + - @` are neutralised (spreadsheet formula injection) | `finance.test.tsx` |
| Personal data | ✅ | Emails only on admin finance endpoints; the names endpoint doesn't return them; students can delete their account | `UserAdministrationTests` |
| Secrets | ✅ | None in the repository or images; configuration only from the environment; secret scanning in CI | CI "Dependency and secret scan" (Trivy) |
| Dependencies and images | ✅ | Trivy scans dependencies and every image on each pull request (fails on fixable CRITICAL); Dependabot weekly/monthly; images run as non-root with read-only file systems | CI; **new:** CI fails if a security version pin becomes older than Spring Boot's own |
| Logging | ✅ | No tokens, secrets or emails in logs (reviewed); correlation ids on every request | Review |
| Containers | ✅ | Non-root, read-only, `no-new-privileges`; database not published; gateway and web bound to localhost in Compose | CI image checks |

## Before go-live (on the real servers)

These can't be done in code. Phase 16 (deployment) walks through each one.

1. **Reset the leaked credentials** shared during development: the Google OAuth client secret, the ngrok authtoken
   and the PayHere sandbox merchant secret. Put the new values only in the server's environment.
2. Run every service with `SPRING_PROFILES_ACTIVE=prod`.
3. Generate and set `IDENTITY_JWT_SIGNING_KEYS` and `CATALOG_MEDIA_SIGNING_KEY` (the services won't start without them
   under `prod`). Keep a copy somewhere safe: changing them signs everyone out and breaks image links for 10 minutes.
4. `PAYMENT_PROVIDER=PAYHERE`, `PAYHERE_SANDBOX=false`, the live merchant id and secret, and `PUBLIC_BASE_URL` with
   https. Add the live domain in PayHere (Settings → Domains & Credentials); for the Refund API, whitelist the server's
   domain or IP for the API key.
5. Add the live address to the Google OAuth client's redirect URIs (`https://<domain>/api/auth/google/callback`).
6. Set `ADMIN_BOOTSTRAP_EMAIL` to the client's admin address, sign in once, then remove it.
7. Never deploy `docker-compose.e2e.yml` or `docker-compose.db-port.yml`.
8. Keep only ports 80 and 443 open to the internet; SSH from known addresses only.
9. If a load balancer is put in front of Caddy, configure `trusted_proxies` (see ADR-015).
10. Database backups encrypted and tested by restoring once.

## Not covered by automated checks

- Penetration test by an independent tester (recommended before taking real payments at scale).
- Manual keyboard and screen-reader checks of the exam screens (axe finds only part of accessibility problems).
- PayHere live payments and refunds end to end (needs the live account; sandbox flow tested earlier).
