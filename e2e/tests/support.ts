import { expect, type Page } from '@playwright/test';

/** The bootstrap admin (ADMIN_BOOTSTRAP_EMAIL in docker-compose.e2e.yml). */
export const ADMIN_EMAIL = 'admin@e2e.test';
export const SAMPLE_TITLE = 'O/L Science Revision (sample)';

/** A new student for each test, so free attempts and history never leak between tests. */
export function newStudentEmail(label: string): string {
  return `${label}.${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}@e2e.test`;
}

/** Signs in through the real flow: our sign-in page → (fake) Google → identity-service callback → token refresh. */
export async function signIn(page: Page, email: string, name = 'E2E Student', returnTo = '/'): Promise<void> {
  await page.goto(`/signin?returnTo=${encodeURIComponent(returnTo)}`);
  const origin = new URL(page.url()).origin;
  await page.getByRole('button', { name: 'Continue with Google' }).click();
  await expect(page.getByRole('heading', { name: 'Test sign-in (not Google)' })).toBeVisible();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Name').fill(name);
  const signedIn = page.waitForResponse((r) => r.url().endsWith('/api/auth/token') && r.status() === 200);
  await page.getByRole('button', { name: 'Continue', exact: true }).click();
  await signedIn;
  await page.waitForURL((u) => u.origin === origin && !u.pathname.startsWith('/api/'));
}

export interface ApiResult {
  status: number;
  body: string;
}

/**
 * Calls the API as the signed-in user, from inside the page (same origin, same cookie), the way the web app does.
 * Used only to arrange data the test isn't about; the behaviour under test always goes through the UI.
 */
export async function api(page: Page, method: string, path: string, body?: unknown): Promise<ApiResult> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const t = await fetch('/api/auth/token', { method: 'POST', headers: { 'X-Requested-With': 'fetch' } });
      if (!t.ok) return { status: t.status, body: await t.text() };
      const { accessToken } = (await t.json()) as { accessToken: string };
      const r = await fetch(`/api${path}`, {
        method,
        headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: r.status, body: await r.text() };
    },
    { method, path, body },
  );
}

export interface PublicExam {
  id: string;
  slug: string;
  title: string;
}

/** The sample exam published by 00-admin-publishes-exam.spec.ts, from the public catalogue. */
export async function sampleExam(page: Page): Promise<PublicExam> {
  const r = await page.request.get('/api/exams?size=100');
  expect(r.status()).toBe(200);
  const data = (await r.json()) as PublicExam[] | { items?: PublicExam[]; content?: PublicExam[] };
  const exams = Array.isArray(data) ? data : (data.items ?? data.content ?? []);
  const exam = exams.find((e) => e.title === SAMPLE_TITLE);
  expect(exam, `"${SAMPLE_TITLE}" is published (the setup project publishes it)`).toBeTruthy();
  return exam!;
}

/** "Question 1 of 5" → 5. */
export async function questionTotal(page: Page): Promise<number> {
  const text = await page
    .getByText(/^Question \d+ of \d+$/)
    .first()
    .textContent();
  return Number(/of (\d+)/.exec(text ?? '')?.[1] ?? NaN);
}

/** Picks the first answer of the question on screen (checkbox questions also need "Check answer" in Practice). */
export async function chooseFirstAnswer(page: Page): Promise<'single' | 'multi'> {
  const checkbox = page.getByRole('checkbox').first();
  if ((await checkbox.count()) > 0) {
    await checkbox.click();
    return 'multi';
  }
  await page.getByRole('radio').first().click();
  return 'single';
}
