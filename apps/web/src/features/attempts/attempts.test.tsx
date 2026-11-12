import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { examDetail } from '@/test/fixtures';
import { clearSession } from '@/lib/auth/session';
import { countChoices, minuteChoices } from './modes';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const EXAM_ID = '0190d5e2-0000-7000-8000-000000000001';

const options = ['Arteries', 'Veins', 'Capillaries', 'Lymph vessels'].map((text, i) => ({
  label: String.fromCharCode(65 + i),
  bodyHtml: `<p>${text}</p>`,
  mediaUrl: null,
  mediaAlt: null,
}));

function question(position: number, extra: Record<string, unknown> = {}) {
  return {
    position,
    topicId: 't1',
    topicTitle: 'Dosage by weight',
    difficulty: 'SIMPLE',
    answerType: 'SINGLE',
    estSeconds: 40,
    stemHtml: `<p>Question text ${position}</p>`,
    stemMediaUrl: null,
    stemMediaAlt: null,
    options,
    selected: [],
    flagged: false,
    answered: false,
    review: null,
    level: 2,
    ...extra,
  };
}

const review = (correct: boolean | null, correctLabels = ['A']) => ({
  correct,
  correctLabels,
  explanationHtml: '<p>Arteries carry blood away from the heart.</p>',
  keyPoints: ['Veins have valves'],
  definitions: [],
  awarded: correct ? 1 : 0,
  marks: 1,
});

function attempt(extra: Record<string, unknown> = {}) {
  return {
    id: 'att-1',
    examId: EXAM_ID,
    examTitle: 'Nursing Licensure Review',
    examSlug: 'nursing-licensure',
    mode: 'PRACTICE',
    status: 'IN_PROGRESS',
    finishReason: null,
    feedback: 'IMMEDIATE',
    sizeBy: 'COUNT',
    requestedCount: 3,
    requestedMinutes: null,
    estimatedSeconds: 120,
    startedAt: '2026-10-26T08:00:00Z',
    expiresAt: null,
    finishedAt: null,
    serverNow: new Date().toISOString(),
    topics: [{ id: 't1', title: 'Dosage by weight' }],
    questions: [question(1), question(2), question(3)],
    result: null,
    plannedCount: 3,
    ...extra,
  };
}

const result = {
  score: 1,
  maxScore: 3,
  percent: 33,
  correct: 1,
  incorrect: 1,
  unanswered: 1,
  passMarkPct: null,
  passed: null,
  durationSeconds: 95,
  byTopic: [
    {
      topicId: 't1',
      title: 'Dosage by weight',
      total: 2,
      correct: 1,
      incorrect: 1,
      unanswered: 0,
      percent: 50,
      level: 2,
      levelLabel: 'Medium',
    },
    {
      topicId: 't2',
      title: 'IV flow rates',
      total: 1,
      correct: 0,
      incorrect: 0,
      unanswered: 1,
      percent: 0,
      level: 1,
      levelLabel: 'Easy',
    },
  ],
};

const freeAccess = {
  [EXAM_ID]: {
    available: true,
    premium: false,
    modes: [
      { mode: 'PRACTICE', access: 'FREE', freeAttempts: 3, used: 0, left: 3 },
      { mode: 'MOCK', access: 'FREE', freeAttempts: 1, used: 0, left: 1 },
    ],
  },
};

describe('starting an exam', () => {
  it('chooses topics and a question count, then starts with one idempotency key', async () => {
    const started: { body: unknown; key: string | null }[] = [];
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: EXAM_ID })),
      'GET /api/access/exams': () => jsonResponse(freeAccess),
      'POST /api/attempts': ({ body, init }) => {
        started.push({ body, key: new Headers(init?.headers).get('Idempotency-Key') });
        return jsonResponse(attempt(), 201);
      },
      'GET /api/attempts/att-1': () => jsonResponse(attempt()),
    });
    renderRoute('/exams/nursing-licensure/start/practice');
    expect(await screen.findByRole('heading', { level: 1, name: 'Start a practice' })).toBeInTheDocument();
    expect(await screen.findByText('3 of 3 free attempts left')).toBeInTheDocument();

    // Both topics start chosen; untick one.
    await userEvent.click(screen.getByRole('checkbox', { name: 'IV flow rates' }));
    expect(screen.getByText('1 of 2 topics chosen.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: '20' }));
    await userEvent.click(screen.getByRole('button', { name: 'Start practice' }));

    expect(await screen.findByText('Question 1 of 3')).toBeInTheDocument();
    expect(started).toHaveLength(1);
    expect(started[0]?.body).toEqual({ examId: EXAM_ID, mode: 'PRACTICE', topicIds: ['t1'], sizeBy: 'COUNT', questionCount: 20 });
    expect(started[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('can be sized by time, and needs at least one topic', async () => {
    const bodies: unknown[] = [];
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: EXAM_ID })),
      'GET /api/access/exams': () => jsonResponse(freeAccess),
      'POST /api/attempts': ({ body }) => {
        bodies.push(body);
        return jsonResponse(
          {
            type: 'about:blank',
            title: 'x',
            status: 403,
            code: 'FREE_ALLOWANCE_USED',
            detail: 'You’ve used your free Practice attempts for this exam.',
          },
          403,
        );
      },
    });
    renderRoute('/exams/nursing-licensure/start/practice');
    await userEvent.click(await screen.findByRole('button', { name: 'Clear' }));
    expect(screen.getByText('Choose at least one topic.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Start practice' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Select all' }));

    await userEvent.click(screen.getByRole('radio', { name: 'Time I have' }));
    await userEvent.click(screen.getByRole('radio', { name: '20 min' }));
    await userEvent.click(screen.getByRole('button', { name: 'Start practice' }));
    expect(await screen.findByText('Premium needed')).toBeInTheDocument();
    expect(bodies[0]).toEqual({ examId: EXAM_ID, mode: 'PRACTICE', topicIds: [], sizeBy: 'TIME', minutes: 20 });
  });

  it('offers to continue a mock exam already in progress', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: EXAM_ID })),
      'GET /api/access/exams': () => jsonResponse(freeAccess),
      'POST /api/attempts': () =>
        jsonResponse(
          { type: 'about:blank', title: 'Conflict', status: 409, code: 'CONFLICT', detail: 'In progress', attemptId: 'mock-9' },
          409,
        ),
    });
    renderRoute('/exams/nursing-licensure/start/mock');
    // The mock exam in the fixture lets nobody choose topics.
    expect(await screen.findByText('Every topic of the exam is included (2).')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Start mock exam' }));
    expect(await screen.findByRole('link', { name: 'Continue your mock exam' })).toHaveAttribute('href', '/attempts/mock-9');
  });

  it('offers only counts and minutes the exam type allows', () => {
    expect(countChoices(30)).toEqual([5, 10, 20, 30]);
    expect(countChoices(12)).toEqual([5, 10, 12]);
    expect(countChoices(3)).toEqual([3]);
    expect(minuteChoices(null)).toEqual([10, 20, 30]);
    expect(minuteChoices(25 * 60)).toEqual([10, 20]);
    expect(minuteChoices(5 * 60)).toEqual([5]);
  });
});

describe('practice: one question at a time with instant answers', () => {
  it('checks an answer as soon as it is chosen and shows the correct answer and explanation', async () => {
    const saved: unknown[] = [];
    mockApi({
      ...signedIn(),
      'GET /api/attempts/att-1': () => jsonResponse(attempt()),
      'PUT /api/attempts/att-1/answers/1': ({ body }) => {
        saved.push(body);
        return jsonResponse(question(1, { selected: ['B'], answered: true, review: review(false) }));
      },
    });
    renderRoute('/attempts/att-1');
    expect(await screen.findByText('Question text 1')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: /Veins/ }));

    expect(await screen.findByText('Not quite. The answer is A.')).toBeInTheDocument();
    expect(screen.getByText('Arteries carry blood away from the heart.')).toBeInTheDocument();
    expect(screen.getByText('Correct answer')).toBeInTheDocument();
    expect(screen.getByText('Your answer — incorrect')).toBeInTheDocument();
    expect(saved[0]).toMatchObject({ selected: ['B'] });
    expect(screen.getByText('0 correct of 1 answered')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /Next question/ }));
    expect(screen.getByText('Question text 2')).toBeInTheDocument();
  });

  it('waits for “Check answer” when several answers can be right', async () => {
    const saved: unknown[] = [];
    mockApi({
      ...signedIn(),
      'GET /api/attempts/att-1': () => jsonResponse(attempt({ questions: [question(1, { answerType: 'MULTI' })] })),
      'PUT /api/attempts/att-1/answers/1': ({ body }) => {
        saved.push(body);
        return jsonResponse(
          question(1, { answerType: 'MULTI', selected: ['A', 'C'], answered: true, review: review(true, ['A', 'C']) }),
        );
      },
    });
    renderRoute('/attempts/att-1');
    await userEvent.click(await screen.findByRole('checkbox', { name: /Arteries/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: /Capillaries/ }));
    expect(saved).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: 'Check answer' }));
    expect(await screen.findByText('Correct!')).toBeInTheDocument();
    expect(saved[0]).toMatchObject({ selected: ['A', 'C'] });
  });

  it('finishes early with a warning about unanswered questions, then shows the result', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/attempts/att-1': () => jsonResponse(attempt()),
      'POST /api/attempts/att-1/submit': () =>
        jsonResponse(
          attempt({
            status: 'SCORED',
            finishReason: 'SUBMITTED',
            result,
            questions: [
              question(1, { selected: ['A'], answered: true, review: review(true) }),
              question(2, { selected: ['B'], answered: true, review: review(false) }),
              question(3, { topicId: 't2', topicTitle: 'IV flow rates', review: review(null) }),
            ],
          }),
        ),
    });
    renderRoute('/attempts/att-1');
    await userEvent.click(await screen.findByRole('button', { name: 'Finish' }));
    const dialog = await screen.findByRole('dialog', { name: 'Finish this session?' });
    expect(within(dialog).getByText(/3 questions are not answered yet/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'See results' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Your result' })).toBeInTheDocument();
    expect(screen.getByText('33%')).toBeInTheDocument();
    expect(screen.getByText(/Revise next:/).parentElement).toHaveTextContent('IV flow rates');
    expect(screen.getByRole('meter', { name: 'Dosage by weight: 1 of 2 correct' })).toHaveAttribute('aria-valuenow', '50');
    expect(screen.getAllByText(/Your level:/)).toHaveLength(2);

    await userEvent.click(screen.getByRole('tab', { name: 'Incorrect (1)' }));
    expect(screen.queryByText('Question text 1')).not.toBeInTheDocument();
    expect(screen.getByText('Question text 2')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Another practice/ })).toHaveAttribute(
      'href',
      '/exams/nursing-licensure/start/practice',
    );
  });
});

describe('adaptive exam', () => {
  it('fetches each next question after an answer, chosen for the student’s level', async () => {
    let given = 1;
    const adaptive = () =>
      attempt({
        mode: 'USER_FRIENDLY',
        plannedCount: 3,
        questions: Array.from({ length: given }, (_, i) =>
          i < given - 1
            ? question(i + 1, { selected: ['A'], answered: true, review: review(true) })
            : question(i + 1, { level: i === 1 ? 3 : 2 }),
        ),
      });
    mockApi({
      ...signedIn(),
      'GET /api/attempts/att-1': () => jsonResponse(adaptive()),
      'PUT /api/attempts/att-1/answers/1': () => {
        given = 2;
        return jsonResponse(question(1, { selected: ['A'], answered: true, review: review(true) }));
      },
    });
    renderRoute('/attempts/att-1');
    expect(await screen.findByText('Question 1 of 3')).toBeInTheDocument();
    expect(screen.getByText('Chosen for your Medium level on this topic')).toBeInTheDocument();
    // No skipping ahead: the next question doesn't exist until this one is answered.
    expect(screen.queryByRole('button', { name: /Skip/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: /Arteries/ }));
    expect(await screen.findByText('Correct!')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /Next question/ })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: /Next question/ }));
    expect(await screen.findByText('Question 2 of 3')).toBeInTheDocument();
    expect(screen.getByText('Chosen for your Hard level on this topic')).toBeInTheDocument();
  });
});

describe('mock exam', () => {
  const mockAttempt = (extra: Record<string, unknown> = {}) =>
    attempt({
      mode: 'MOCK',
      feedback: 'AFTER_SUBMIT',
      expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
      serverNow: new Date().toISOString(),
      ...extra,
    });

  it('saves answers without revealing them, flags questions and reviews before submitting', async () => {
    const saved: unknown[] = [];
    mockApi({
      ...signedIn(),
      'GET /api/attempts/att-1': () => jsonResponse(mockAttempt()),
      'PUT /api/attempts/att-1/answers/1': ({ body }) => {
        saved.push(body);
        const b = body as { selected?: string[]; flagged?: boolean };
        return jsonResponse(
          question(1, { selected: b.selected ?? [], answered: (b.selected ?? []).length > 0, flagged: b.flagged ?? false }),
        );
      },
      'PUT /api/attempts/att-1/answers/2': ({ body }) => {
        saved.push(body);
        return jsonResponse(question(2, { flagged: true }));
      },
    });
    renderRoute('/attempts/att-1');
    expect(await screen.findByText(/Time left:/)).toBeInTheDocument();
    expect(screen.getByText(/Time left:/).parentElement).toHaveTextContent(/(29:5\d|30:00)/);

    await userEvent.click(screen.getByRole('radio', { name: /Veins/ }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toMatchObject({ selected: ['B'] });
    // Nothing about right or wrong while the paper is running.
    expect(screen.queryByText(/Correct/)).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next →' }));
    await userEvent.click(screen.getByRole('button', { name: 'Flag for review' }));
    await waitFor(() => expect(saved[1]).toMatchObject({ flagged: true }));

    const map = screen.getByRole('complementary', { name: 'Question map' });
    expect(within(map).getByRole('button', { name: 'Question 1, answered' })).toBeInTheDocument();
    expect(within(map).getByRole('button', { name: 'Question 2, not answered, flagged' })).toHaveAttribute(
      'aria-current',
      'step',
    );

    await userEvent.click(screen.getByRole('button', { name: 'Submit…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Submit your mock exam?' });
    expect(within(dialog).getByText('Not answered (2)')).toBeInTheDocument();
    expect(within(dialog).getByText('Flagged (1)')).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Go to question 3' }));
    expect(screen.getByText('Question 3 of 3')).toBeInTheDocument();
  });

  it('hands the paper in when the server’s time is up', async () => {
    let submitted = 0;
    const now = Date.now();
    mockApi({
      ...signedIn(),
      // The device clock is an hour fast: the server says there is one second left.
      'GET /api/attempts/att-1': () =>
        jsonResponse(
          mockAttempt({
            serverNow: new Date(now - 3_600_000).toISOString(),
            expiresAt: new Date(now - 3_600_000 + 1000).toISOString(),
          }),
        ),
      'POST /api/attempts/att-1/submit': () => {
        submitted++;
        return jsonResponse(
          mockAttempt({ status: 'SCORED', finishReason: 'EXPIRED', result, questions: [question(1, { review: review(null) })] }),
        );
      },
    });
    renderRoute('/attempts/att-1');
    expect(await screen.findByText('Question 1 of 3')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 1, name: 'Your result' }, { timeout: 4000 })).toBeInTheDocument();
    expect(screen.getByText('Time ran out')).toBeInTheDocument();
    expect(submitted).toBe(1);
  });
});
