import { expect, test } from '@playwright/test';
import { ADMIN_EMAIL, SAMPLE_TITLE, sampleExam, signIn } from './support';

/**
 * Setup project: the admin creates the sample exam and publishes it through the admin screens. The other journeys
 * use this exam. New exams need no code: the public page and its address come from the catalogue alone.
 */
test('admin creates the sample exam and publishes it; its public page appears', async ({ page }) => {
  await signIn(page, ADMIN_EMAIL, 'E2E Admin', '/admin/exams');
  await page.goto('/admin/exams');
  await expect(page.getByRole('heading', { name: 'Exams', level: 1 })).toBeVisible();

  await page.getByRole('button', { name: 'Create sample exam (O/L Science)' }).click();
  await page.waitForURL(/\/admin\/exams\/[^/]+\/publish$/);

  await page.getByRole('button', { name: 'Publish exam' }).click();
  const dialog = page.getByRole('dialog', { name: `Publish ${SAMPLE_TITLE}?` });
  await expect(dialog).toBeVisible();
  const address = (await dialog.textContent())?.match(/\/exams\/([a-z0-9-]+)/)?.[1];
  expect(address, 'the dialog names the exam’s address').toBeTruthy();
  await dialog.getByRole('button', { name: 'Publish', exact: true }).click();
  await expect(dialog).toBeHidden();

  // Anyone can open the new address, signed in or not.
  await page.context().clearCookies();
  await page.goto(`/exams/${address}`);
  await expect(page.getByRole('heading', { name: SAMPLE_TITLE, level: 1 })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Practice' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Mock exam' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign in to start' }).first()).toBeVisible();

  // subscription-service learns about the publication from the catalogue's event feed (polled every 2 s); until
  // then it treats the exam as unavailable. Wait for that, so the journeys don't race it.
  const exam = await sampleExam(page);
  await expect
    .poll(async () => (await page.request.get(`/api/access/exams?examIds=${exam.id}`)).text(), { timeout: 30_000 })
    .toContain('PRACTICE');
});
