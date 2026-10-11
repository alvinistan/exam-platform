import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const admin = { id: 'admin-1', roles: ['ADMIN', 'STUDENT'], displayName: 'Kani Admin', email: 'kani@example.com' };

const library = [
  {
    id: 's1',
    title: 'Pharmacology',
    code: null,
    description: null,
    status: 'ACTIVE',
    version: 0,
    linkedExamCount: 1,
    modules: [
      {
        id: 'm1',
        subjectId: 's1',
        title: 'Drug calculations',
        displayOrder: 0,
        status: 'ACTIVE',
        version: 0,
        topics: [
          { id: 't1', moduleId: 'm1', title: 'Dosage by weight', displayOrder: 0, status: 'ACTIVE', version: 0, prerequisiteTopicIds: [], publishedQuestionCount: 3, trialQuestionCount: 0 },
          { id: 't2', moduleId: 'm1', title: 'IV flow rates', displayOrder: 1, status: 'ACTIVE', version: 0, prerequisiteTopicIds: [], publishedQuestionCount: 0, trialQuestionCount: 0 },
        ],
      },
    ],
  },
];

function summary(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    bank: 'MAIN',
    status: 'DRAFT',
    topicId: 't1',
    topicTitle: 'Dosage by weight',
    moduleTitle: 'Drug calculations',
    subjectTitle: 'Pharmacology',
    difficulty: 'SIMPLE',
    answerType: 'SINGLE',
    stemText: `Question ${id} text`,
    versionNo: 1,
    hasDraftChanges: false,
    tags: ['dosage'],
    updatedAt: '2026-10-11T08:00:00Z',
    updatedBy: 'admin-1',
    ...extra,
  };
}

function detail(extra: Record<string, unknown> = {}) {
  return {
    id: 'q1',
    bank: 'MAIN',
    status: 'DRAFT',
    topicId: 't1',
    topic: { subjectId: 's1', subjectTitle: 'Pharmacology', moduleId: 'm1', moduleTitle: 'Drug calculations', topicTitle: 'Dosage by weight', active: true },
    answerType: 'SINGLE',
    stemHtml: '<p>A child weighs <strong>20 kg</strong>. Dose?</p>',
    stemMediaKey: null,
    stemMediaUrl: null,
    stemMediaAlt: null,
    options: ['50 mg', '100 mg', '150 mg', '200 mg'].map((t, i) => ({
      label: 'ABCD'[i],
      bodyHtml: `<p>${t}</p>`,
      mediaKey: null,
      mediaUrl: null,
      mediaAlt: null,
      isCorrect: i === 1,
      misconceptionId: null,
    })),
    explanationHtml: '<p>20 × 5 = 100.</p>',
    definitions: [],
    keyPoints: ['Multiply weight by dose'],
    difficulty: 'SIMPLE',
    estSeconds: 60,
    marks: 1,
    tags: ['dosage'],
    versionNo: 1,
    publishedVersionNo: null,
    hasDraftChanges: false,
    version: 3,
    validation: { valid: true, problems: [] },
    createdAt: '2026-10-10T08:00:00Z',
    createdBy: 'admin-1',
    updatedAt: '2026-10-11T08:00:00Z',
    updatedBy: 'admin-1',
    history: [{ versionNo: 1, published: false, publishedAt: null, createdBy: 'admin-1', createdAt: '2026-10-10T08:00:00Z' }],
    ...extra,
  };
}

const base = {
  ...signedIn(admin),
  'GET /api/admin/catalog/subjects': () => jsonResponse(library),
  'GET /api/admin/catalog/exams': () => jsonResponse({ items: [], page: { page: 0, size: 25, totalElements: 0, totalPages: 0 } }),
  'GET /api/admin/catalog/misconceptions': () => jsonResponse([]),
  'GET /api/admin/users/names': () => jsonResponse([{ id: 'admin-1', displayName: 'Kani Admin' }]),
};

describe('question list', () => {
  it('lists questions with their place, bank and status, and applies bulk actions', async () => {
    const bulk: unknown[] = [];
    mockApi({
      ...base,
      'GET /api/admin/catalog/questions': () =>
        jsonResponse({
          items: [summary('q1'), summary('q2', { status: 'PUBLISHED', hasDraftChanges: true, bank: 'TRIAL', answerType: 'MULTI' })],
          page: { page: 0, size: 25, totalElements: 2, totalPages: 1 },
        }),
      'POST /api/admin/catalog/questions/bulk': ({ body }) => {
        bulk.push(body);
        return jsonResponse({ updated: 2, createdIds: [] });
      },
    });
    renderRoute('/admin/questions');
    const table = await screen.findByRole('table', { name: 'Questions, 2 in total' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Question q1 text');
    expect(rows[1]).toHaveTextContent('Pharmacology › Drug calculations › Dosage by weight');
    expect(rows[2]).toHaveTextContent('Unpublished changes');
    expect(rows[2]).toHaveTextContent('Trial bank');
    expect(rows[2]).toHaveTextContent('Multi-answer');
    expect(await within(rows[1] as HTMLElement).findByText('Kani Admin')).toBeInTheDocument();
    expect(within(table).getByRole('link', { name: 'Question q2 text' })).toHaveAttribute('href', '/admin/questions/q2');

    await userEvent.click(screen.getByLabelText('Select all on this page'));
    await userEvent.click(within(screen.getByRole('region', { name: 'Bulk actions' })).getByRole('button', { name: 'Change status' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.selectOptions(within(dialog).getByLabelText('New status'), 'ARCHIVED');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Apply to 2' }));
    expect(await screen.findByText('2 question(s) updated.')).toBeInTheDocument();
    expect(bulk[0]).toEqual({ questionIds: ['q1', 'q2'], action: 'SET_STATUS', status: 'ARCHIVED' });
  });

  it('passes filters to the server', async () => {
    const fetchMock = mockApi({
      ...base,
      'GET /api/admin/catalog/questions': () => jsonResponse({ items: [], page: { page: 0, size: 25, totalElements: 0, totalPages: 0 } }),
    });
    renderRoute('/admin/questions');
    expect(await screen.findByRole('heading', { name: 'No questions yet' })).toBeInTheDocument();
    await screen.findByRole('option', { name: 'Pharmacology' });
    await userEvent.selectOptions(screen.getByLabelText('Subject'), 's1');
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'REVIEWED');
    await waitFor(() =>
      expect(fetchMock.mock.calls.map((c) => String(c[0])).some((u) => u.includes('subjectId=s1') && u.includes('status=REVIEWED'))).toBe(true),
    );
    expect(await screen.findByRole('heading', { name: 'No questions match' })).toBeInTheDocument();
  });
});

describe('question editor', () => {
  it('shows what blocks review and only offers moves that are allowed', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/questions/q1': () =>
        jsonResponse(detail({ validation: { valid: false, problems: ['Add 4 or 5 options (there are 3).'] } })),
    });
    renderRoute('/admin/questions/q1');
    expect(await screen.findByText('Add 4 or 5 options (there are 3).')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark as reviewed' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument();
    expect(await screen.findByText(/Created by Kani Admin/)).toBeInTheDocument();
  });

  it('moves a complete draft to reviewed, then publishes after confirmation', async () => {
    let current = detail();
    const moves: unknown[] = [];
    mockApi({
      ...base,
      'GET /api/admin/catalog/questions/q1': () => jsonResponse(current),
      'POST /api/admin/catalog/questions/q1/transitions': ({ body }) => {
        moves.push(body);
        const to = (body as { to: string }).to;
        current = detail({ status: to, version: current.version + 1, ...(to === 'PUBLISHED' ? { publishedVersionNo: 1 } : {}) });
        return jsonResponse(current);
      },
    });
    renderRoute('/admin/questions/q1');
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as reviewed' }));
    expect(await screen.findByText('Moved to reviewed.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Publish' }));
    const dialog = await screen.findByRole('dialog', { name: 'Publish this question?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(await screen.findByText('Version 1 is published.')).toBeInTheDocument();
    expect(moves).toEqual([{ to: 'REVIEWED' }, { to: 'PUBLISHED' }]);
  });

  it('explains when a published exam still needs the question', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/questions/q1': () => jsonResponse(detail({ status: 'PUBLISHED', publishedVersionNo: 1 })),
      'POST /api/admin/catalog/questions/q1/transitions': () =>
        jsonResponse(
          { type: 'about:blank', title: 'Cannot publish yet', status: 409, code: 'PUBLISH_BLOCKED', detail: 'This change would leave the published exam "Nursing" unable to run.', blockers: ['Practice needs at least 5 published questions; 4 published.'] },
          409,
        ),
    });
    renderRoute('/admin/questions/q1');
    await userEvent.click(await screen.findByRole('button', { name: 'Archive' }));
    const dialog = await screen.findByRole('dialog', { name: 'Archive this question?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Archive' }));
    expect(await within(dialog).findByText('Practice needs at least 5 published questions; 4 published.')).toBeInTheDocument();
  });

  it('saves edits with the version it was loaded at', async () => {
    const saved: { body: unknown; ifMatch: string | null }[] = [];
    mockApi({
      ...base,
      'GET /api/admin/catalog/questions/q1': () => jsonResponse(detail()),
      'PUT /api/admin/catalog/questions/q1': ({ body, init }) => {
        saved.push({ body, ifMatch: new Headers(init?.headers).get('If-Match') });
        return jsonResponse(detail({ difficulty: 'HARD', version: 4 }));
      },
    });
    renderRoute('/admin/questions/q1');
    await userEvent.selectOptions(await screen.findByLabelText('Difficulty'), 'HARD');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(saved[0]?.ifMatch).toBe('"3"');
    expect(saved[0]?.body).toMatchObject({
      topicId: 't1',
      bank: 'MAIN',
      difficulty: 'HARD',
      stemHtml: '<p>A child weighs <strong>20 kg</strong>. Dose?</p>',
      keyPoints: ['Multiply weight by dose'],
      tags: ['dosage'],
    });
    expect((saved[0]?.body as { options: { isCorrect: boolean }[] }).options.map((o) => o.isCorrect)).toEqual([false, true, false, false]);
  });

  it('shows field problems the server reports', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/questions/q1': () => jsonResponse(detail()),
      'PUT /api/admin/catalog/questions/q1': () =>
        jsonResponse(
          { type: 'about:blank', title: 'Validation failed', status: 400, code: 'VALIDATION_FAILED', detail: 'One or more fields are invalid.', errors: [{ field: 'estSeconds', message: 'must be between 10 and 600' }] },
          400,
        ),
    });
    renderRoute('/admin/questions/q1');
    await userEvent.click(await screen.findByRole('button', { name: 'Save' }));
    expect(await screen.findByText('One field needs attention.')).toBeInTheDocument();
    expect(screen.getByLabelText('Solving time (seconds)')).toHaveAttribute('aria-invalid', 'true');
  });

  it('previews the question as a student, answering and in review', async () => {
    mockApi({ ...base, 'GET /api/admin/catalog/questions/q1': () => jsonResponse(detail()) });
    renderRoute('/admin/questions/q1');
    const preview = await screen.findByRole('complementary', { name: 'Student preview' });
    expect(within(preview).getByText('Choose one answer.')).toBeInTheDocument();
    await userEvent.click(within(preview).getByRole('radio', { name: /^50 mg/ }));
    expect(within(preview).getByRole('radio', { name: /^50 mg/ })).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(within(preview).getByRole('button', { name: 'Review' }));
    expect(within(preview).getByText('Correct answer')).toBeInTheDocument();
    expect(within(preview).getByText('Your answer — incorrect')).toBeInTheDocument();
    expect(within(preview).getByText('Explanation')).toBeInTheDocument();
    expect(within(preview).getByText('Multiply weight by dose')).toBeInTheDocument();
  });

  it('asks for a topic before creating a question', async () => {
    const posted: unknown[] = [];
    mockApi({
      ...base,
      'POST /api/admin/catalog/questions': ({ body }) => {
        posted.push(body);
        return jsonResponse(detail({ id: 'q9' }), 201);
      },
      'GET /api/admin/catalog/questions/q9': () => jsonResponse(detail({ id: 'q9' })),
    });
    renderRoute('/admin/questions/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Create draft' }));
    expect(await screen.findByText('Choose a topic.')).toBeInTheDocument();
    expect(posted).toHaveLength(0);
    await screen.findByRole('option', { name: 'IV flow rates' });
    await userEvent.selectOptions(screen.getByLabelText('Topic'), 't2');
    await userEvent.click(screen.getByLabelText('Trial bank', { exact: false }));
    await userEvent.click(screen.getByRole('button', { name: 'Create draft' }));
    expect(await screen.findByText('Draft saved.')).toBeInTheDocument();
    expect(posted[0]).toMatchObject({ topicId: 't2', bank: 'TRIAL', answerType: 'SINGLE', difficulty: 'MEDIUM', estSeconds: 60 });
    expect((posted[0] as { options: unknown[] }).options).toHaveLength(4);
  });
});

describe('question import', () => {
  it('checks a file, lists row problems and imports the valid rows on confirmation', async () => {
    const report = {
      importId: 'imp-1',
      examId: 'e1',
      bank: 'MAIN',
      fileName: 'q.csv',
      totalRows: 3,
      validRows: 2,
      errors: [{ row: 4, column: 'correct', message: 'A SINGLE question needs exactly one correct letter.' }],
      status: 'PENDING',
      expiresAt: '2026-10-12T08:00:00Z',
      createdQuestionIds: [],
    };
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams': () =>
        jsonResponse({ items: [{ id: 'e1', title: 'Nursing Licensure Review' }], page: { page: 0, size: 25, totalElements: 1, totalPages: 1 } }),
      'POST /api/admin/catalog/question-imports': () => jsonResponse(report, 201),
      'POST /api/admin/catalog/question-imports/imp-1/confirm': () =>
        jsonResponse({ ...report, status: 'CONFIRMED', createdQuestionIds: ['a', 'b'] }),
    });
    renderRoute('/admin/questions/import');
    await screen.findByRole('option', { name: 'Nursing Licensure Review' });
    await userEvent.selectOptions(screen.getByLabelText('Exam'), 'e1');
    await userEvent.upload(screen.getByLabelText(/File \(.xlsx or .csv/), new File(['x'], 'q.csv', { type: 'text/csv' }));
    await userEvent.click(screen.getByRole('button', { name: 'Check file' }));
    const table = await screen.findByRole('table', { name: 'Problems found, by row' });
    expect(table).toHaveTextContent('A SINGLE question needs exactly one correct letter.');
    await userEvent.click(screen.getByRole('button', { name: 'Import 2 question(s) as drafts' }));
    expect(await screen.findByText('2 draft question(s) created.')).toBeInTheDocument();
  });
});
