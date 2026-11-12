import { expect, test, type Page } from '@playwright/test';
import { ADMIN_EMAIL, api, chooseFirstAnswer, newStudentEmail, questionTotal, sampleExam, signIn } from './support';

async function startMock(page: Page, slug: string) {
  await page.goto(`/exams/${slug}/start/mock`);
  await expect(page.getByRole('heading', { name: 'Start a mock exam', level: 1 })).toBeVisible();
  await page.getByRole('radio', { name: 'Number of questions' }).click();
  await page.getByRole('radiogroup', { name: 'Questions' }).getByRole('radio', { name: '5', exact: true }).click();
  await page.getByRole('button', { name: 'Start mock exam' }).click();
  await page.waitForURL(/\/attempts\/[0-9a-f-]+$/);
}

function mockCard(page: Page) {
  return page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'Mock exam', exact: true }) });
}

test('mock exam: server timer, no answers before submitting, review screen, then the paid plan unlocks more', async ({
  page,
  browser,
}) => {
  const exam = await sampleExam(page);
  await signIn(page, newStudentEmail('mock'), 'Mock Student');

  // --- The one free mock exam -----------------------------------------------------------------------------------
  await startMock(page, exam.slug);
  await expect(page.getByText(/^Time left: /)).toBeAttached();
  const total = await questionTotal(page);
  expect(total).toBe(5);
  for (let n = 1; n < total; n++) {
    await expect(page.getByText(`Question ${n} of ${total}`, { exact: true })).toBeVisible();
    await chooseFirstAnswer(page);
    await expect(page.getByText(`${n} answered`, { exact: false })).toBeVisible();
    // Nothing gives the answer away during a mock exam.
    await expect(page.getByText(/^(Correct — your answer|Correct answer|Your answer — incorrect)$/)).toHaveCount(0);
    await page.getByRole('button', { name: 'Next →' }).click();
  }
  // The last question is left unanswered on purpose.
  await page.getByRole('button', { name: 'Review and submit' }).click();
  const review = page.getByRole('dialog', { name: 'Submit your mock exam?' });
  await expect(review).toContainText(`${total - 1} of ${total} answered`);
  await review.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Your result', level: 1 })).toBeVisible();
  const stat = (label: string) =>
    page
      .locator('dl > div')
      .filter({ has: page.locator('dt', { hasText: label }) })
      .locator('dd');
  await expect(stat('Not answered')).toHaveText('1');

  // A reload shows the same finished result: the attempt can't be restarted or submitted twice.
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Your result', level: 1 })).toBeVisible();

  // --- Free mock used: the exam page and the API both refuse a second one -----------------------------------------
  await page.goto(`/exams/${exam.slug}`);
  await expect(mockCard(page).getByRole('button', { name: 'Needs a plan' })).toBeDisabled();
  const refused = await api(page, 'POST', '/attempts', {
    examId: exam.id,
    mode: 'MOCK',
    topicIds: [],
    sizeBy: 'COUNT',
    questionCount: 5,
  });
  expect(refused.status, refused.body).toBe(403);

  // --- The admin puts a plan on sale (arranged through the API; the plan screens have their own tests) -------------
  const planName = `E2E Premium ${Date.now().toString(36)}`;
  const adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL });
  const admin = await adminContext.newPage();
  await signIn(admin, ADMIN_EMAIL, 'E2E Admin');
  const created = await api(admin, 'POST', '/admin/billing/plans', {
    code: `e2e-${Date.now().toString(36)}`,
    name: planName,
    description: 'Every exam, unlimited, for a month.',
    scopeType: 'ALL',
    examIds: [],
    durationMonths: 1,
    priceLkrMinor: 150000,
    priceUsdMinor: 500,
    practiceLimit: null,
    mockLimit: null,
    adaptiveLimit: null,
    active: true,
    displayOrder: 1,
  });
  expect(created.status, created.body).toBe(201);

  // --- The student buys it with the labelled TEST provider ----------------------------------------------------------
  await page.goto('/plans');
  await page.getByRole('link', { name: new RegExp(`^Choose ${planName}, `) }).click();
  await expect(page.getByRole('heading', { name: 'Get Premium', level: 1 })).toBeVisible();
  await page.getByLabel('Phone').fill('+94 77 123 4567');
  await page.getByLabel('Address').fill('12 Galle Road');
  await page.getByLabel('City').fill('Colombo');
  await page.getByRole('button', { name: 'Continue to the test payment' }).click();
  await expect(page.getByRole('heading', { name: 'Test payment', level: 1 })).toBeVisible();
  await page.getByRole('button', { name: 'Pay (test)' }).click();
  await expect(page.getByRole('heading', { name: 'Your plan has started', level: 1 })).toBeVisible();

  // The plan unlocks the mock exam straight away, and the earlier result is still there.
  await page.goto(`/exams/${exam.slug}`);
  await expect(mockCard(page).getByText('Unlimited with your plan')).toBeVisible();
  await startMock(page, exam.slug);
  await expect(page.getByText(`Question 1 of 5`, { exact: true })).toBeVisible();

  // The payment has a receipt for the student, and the admin sees it in Payments.
  await page.goto('/account/payments');
  await expect(page.getByRole('heading', { name: 'Payments and receipts', level: 1 })).toBeVisible();
  await page.getByRole('link', { name: 'Receipt' }).first().click();
  await expect(page.getByRole('heading', { name: 'Receipt', level: 1 })).toBeVisible();
  await expect(page.getByText(planName).first()).toBeVisible();

  await admin.goto('/admin/payments');
  await expect(admin.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();
  await expect(admin.getByText(planName).first()).toBeVisible();
  await adminContext.close();
});
