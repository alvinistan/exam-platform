import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { ADMIN_EMAIL, chooseFirstAnswer, newStudentEmail, sampleExam, signIn } from './support';

/**
 * Automated WCAG 2.2 A/AA checks with axe-core on the main screens, with real data. Serious and critical problems
 * fail the test; the rest are attached to the report. Automated checks find only part of what matters, so keyboard
 * and screen-reader checks by hand are still needed (see docs/testing/test-report.md).
 */
async function audit(page: Page, name: string) {
  // Wait until loading placeholders are gone; "network idle" never comes on pages that refresh in the background.
  await page.waitForLoadState('load');
  await expect(page.locator('[aria-busy="true"], .animate-shimmer')).toHaveCount(0);
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']).analyze();
  await test.info().attach(`axe-${name}.json`, {
    body: JSON.stringify(results.violations, null, 2),
    contentType: 'application/json',
  });
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => {
      const nodes = v.nodes
        .slice(0, 4)
        .map(
          (n) => `\n    ${n.target.join(' ')}: ${n.html.slice(0, 200)}\n      ${(n.failureSummary ?? '').replace(/\n/g, ' ')}`,
        );
      return `${v.id} (${v.impact}): ${v.help}${nodes.join('')}`;
    });
  expect(blocking, `${name}: accessibility problems`).toEqual([]);
}

test('public pages', async ({ page }) => {
  const exam = await sampleExam(page);
  for (const [name, path] of [
    ['home', '/'],
    ['catalogue', '/exams'],
    ['exam', `/exams/${exam.slug}`],
    ['plans', '/plans'],
    ['signin', '/signin'],
  ] as const) {
    await page.goto(path);
    await expect(page.locator('h1').first()).toBeVisible();
    await audit(page, name);
  }
});

test('student pages, including a question and a result', async ({ page }) => {
  const exam = await sampleExam(page);
  await signIn(page, newStudentEmail('a11y'), 'Accessible Student');
  await page.goto('/dashboard');
  await expect(page.locator('h1').first()).toBeVisible();
  await audit(page, 'dashboard');

  await page.goto(`/exams/${exam.slug}/start/practice`);
  await expect(page.getByRole('heading', { name: 'Start a practice', level: 1 })).toBeVisible();
  await audit(page, 'start');

  await page.getByRole('radio', { name: 'Number of questions' }).click();
  await page.getByRole('radiogroup', { name: 'Questions' }).getByRole('radio', { name: '5', exact: true }).click();
  await page.getByRole('button', { name: 'Start practice' }).click();
  await page.waitForURL(/\/attempts\/[0-9a-f-]+$/);
  await expect(page.getByText('Question 1 of 5', { exact: true })).toBeVisible();
  await audit(page, 'question');

  if ((await chooseFirstAnswer(page)) === 'multi') await page.getByRole('button', { name: 'Check answer' }).click();
  await expect(page.getByText(/^(Correct — your answer|Correct answer)$/).first()).toBeVisible();
  await page
    .getByRole('button', { name: 'Stay on this question' })
    .click()
    .catch(() => undefined);
  await audit(page, 'feedback');

  await page.getByRole('button', { name: 'Finish' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'See results' }).click();
  await expect(page.getByRole('heading', { name: 'Your result', level: 1 })).toBeVisible();
  await audit(page, 'result');

  await page.goto('/progress');
  await expect(page.locator('h1').first()).toBeVisible();
  await audit(page, 'progress');

  await page.goto('/account');
  await expect(page.locator('h1').first()).toBeVisible();
  await audit(page, 'account');
});

test('admin pages', async ({ page }) => {
  await signIn(page, ADMIN_EMAIL, 'E2E Admin');
  for (const [name, path] of [
    ['admin-exams', '/admin/exams'],
    ['admin-questions', '/admin/questions'],
    ['admin-plans', '/admin/plans'],
    ['admin-finance', '/admin/finance'],
    ['admin-payments', '/admin/payments'],
    ['admin-users', '/admin/users'],
  ] as const) {
    await page.goto(path);
    await expect(page.locator('h1').first()).toBeVisible();
    await audit(page, name);
  }
});
