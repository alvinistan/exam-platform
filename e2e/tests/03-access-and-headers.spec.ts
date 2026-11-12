import { expect, test } from '@playwright/test';
import { api, newStudentEmail, sampleExam, signIn } from './support';

test('a student can’t open admin pages or call admin APIs; signed-out callers get 401', async ({ page, request }) => {
  await signIn(page, newStudentEmail('nosy'), 'Nosy Student');
  await page.goto('/admin/exams');
  await expect(page.getByRole('heading', { name: "You don't have access to this page" })).toBeVisible();

  for (const path of ['/admin/catalog/exams', '/admin/billing/payments', '/admin/users', '/admin/billing/finance/summary']) {
    const r = await api(page, 'GET', path);
    expect(r.status, `${path}: ${r.body}`).toBe(403);
  }
  for (const path of ['/api/users/me', '/api/attempts', '/api/subscriptions/payments', '/api/admin/catalog/exams']) {
    expect((await request.get(path)).status(), path).toBe(401);
  }
  // Internal endpoints are never routed to the outside: the web server answers with the app's own page, the
  // gateway has no route.
  const internal = await request.get('/internal/events');
  expect(internal.headers()['content-type']).toContain('text/html');
  expect((await request.get('/api/internal/events')).status()).toBe(404);
});

test('a student can’t read another student’s attempt', async ({ page, browser }) => {
  const exam = await sampleExam(page);
  await signIn(page, newStudentEmail('owner'), 'Owner Student');
  const started = await api(page, 'POST', '/attempts', {
    examId: exam.id,
    mode: 'PRACTICE',
    topicIds: [],
    sizeBy: 'COUNT',
    questionCount: 5,
  });
  expect(started.status, started.body).toBe(201);
  const attemptId = (JSON.parse(started.body) as { id: string }).id;

  const other = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const otherPage = await other.newPage();
  await signIn(otherPage, newStudentEmail('intruder'), 'Intruder Student');
  expect((await api(otherPage, 'GET', `/attempts/${attemptId}`)).status).toBe(404);
  const answer = await api(otherPage, 'PUT', `/attempts/${attemptId}/answers/1`, { selected: ['A'] });
  expect(answer.status).toBe(404);
  expect((await api(otherPage, 'POST', `/attempts/${attemptId}/submit`)).status).toBe(404);
  await other.close();
});

test('the web app is served with the production security headers', async ({ request }) => {
  const r = await request.get('/');
  expect(r.status()).toBe(200);
  const h = r.headers();
  const csp = h['content-security-policy'] ?? '';
  expect(csp).toContain("default-src 'self'");
  expect(csp).toContain("script-src 'self'");
  expect(csp).toContain("frame-ancestors 'none'");
  // The checkout form posts to PayHere and formulas need inline style attributes (Phase 14 fixes).
  expect(csp).toContain('https://www.payhere.lk');
  expect(csp).toContain("style-src-attr 'unsafe-inline'");
  expect(h['x-content-type-options']).toBe('nosniff');
  expect(h['strict-transport-security']).toContain('max-age=');
  expect(h['referrer-policy']).toBe('strict-origin-when-cross-origin');
  expect(h['server']).toBeUndefined();
});

test('pages run without CSP violations or script errors', async ({ page }) => {
  const problems: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error' && /Content Security Policy|Refused to/i.test(m.text())) problems.push(m.text());
  });
  page.on('pageerror', (e) => problems.push(e.message));
  const exam = await sampleExam(page);
  for (const path of ['/', '/exams', `/exams/${exam.slug}`, '/plans', '/signin']) {
    await page.goto(path);
    await expect(page.locator('main, h1').first()).toBeVisible();
  }
  expect(problems).toEqual([]);
});
