import { expect, test, type Page } from '@playwright/test';
import { SAMPLE_TITLE, chooseFirstAnswer, newStudentEmail, questionTotal, sampleExam, signIn } from './support';

async function chooseFiveQuestions(page: Page) {
  await page.getByRole('radio', { name: 'Number of questions' }).click();
  await page.getByRole('radiogroup', { name: 'Questions' }).getByRole('radio', { name: '5', exact: true }).click();
}

/** Answers every question of a Practice or Adaptive session, checking the instant feedback, then opens the result. */
async function answerWithFeedback(page: Page) {
  const total = await questionTotal(page);
  expect(total).toBeGreaterThan(0);
  const progress = page.getByRole('progressbar', { name: 'Questions answered' });
  for (let n = 1; n <= total; n++) {
    await expect(page.getByText(`Question ${n} of ${total}`, { exact: true })).toBeVisible();
    if ((await chooseFirstAnswer(page)) === 'multi') {
      await page.getByRole('button', { name: 'Check answer' }).click();
    }
    await expect(progress).toHaveAttribute('aria-valuenow', String(n));
    // The right answer is shown as soon as the question is answered.
    await expect(page.getByText(/^(Correct — your answer|Correct answer)$/).first()).toBeVisible();
    if (n < total) {
      if (await page.getByText('Moving on in a moment…').isVisible()) {
        await expect(page.getByText(`Question ${n + 1} of ${total}`, { exact: true })).toBeVisible();
      } else {
        await page.getByRole('button', { name: 'Next question' }).click();
      }
    }
  }
  await page.getByRole('button', { name: 'See results' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'See results' }).click();
  await expect(page.getByRole('heading', { name: 'Your result', level: 1 })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'By topic' })).toBeVisible();
  return total;
}

test('student practises with instant feedback and gets a result by topic', async ({ page }) => {
  const exam = await sampleExam(page);
  await signIn(page, newStudentEmail('practice'), 'Practice Student');
  await page.goto(`/exams/${exam.slug}`);
  await expect(page.getByRole('heading', { name: SAMPLE_TITLE, level: 1 })).toBeVisible();
  await page.goto(`/exams/${exam.slug}/start/practice`);
  await expect(page.getByRole('heading', { name: 'Start a practice', level: 1 })).toBeVisible();
  await chooseFiveQuestions(page);
  await page.getByRole('button', { name: 'Start practice' }).click();
  await page.waitForURL(/\/attempts\/[0-9a-f-]+$/);

  const total = await answerWithFeedback(page);
  expect(total).toBe(5);
  await expect(page.getByText(/Score [\d.]+ out of 5/)).toBeVisible();

  // The attempt is in the student's history and progress.
  await page.goto('/dashboard');
  await expect(page.getByText(SAMPLE_TITLE).first()).toBeVisible();
});

test('adaptive exam chooses each next question and gives a result', async ({ page }) => {
  const exam = await sampleExam(page);
  await signIn(page, newStudentEmail('adaptive'), 'Adaptive Student');
  await page.goto(`/exams/${exam.slug}/start/adaptive`);
  await expect(page.getByRole('heading', { name: 'Start an adaptive exam', level: 1 })).toBeVisible();
  await chooseFiveQuestions(page);
  await page.getByRole('button', { name: 'Start adaptive exam' }).click();
  await page.waitForURL(/\/attempts\/[0-9a-f-]+$/);
  await answerWithFeedback(page);
});
