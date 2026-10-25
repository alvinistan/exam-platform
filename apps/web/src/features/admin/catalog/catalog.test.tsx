import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { categoryNursing } from '@/test/fixtures';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const admin = { id: 'admin-1', roles: ['ADMIN', 'STUDENT'], displayName: 'Kani Admin', email: 'kani@example.com' };
const adminCategory = { ...categoryNursing, status: 'ACTIVE', version: 0, examCount: 2, publishedExamCount: 1 };

function modeSettings(mode: string, extra: Record<string, unknown> = {}) {
  return {
    mode,
    enabled: false,
    defaultCount: mode === 'MOCK' ? 40 : 10,
    minCount: mode === 'MOCK' ? 10 : 5,
    maxCount: mode === 'MOCK' ? 200 : 50,
    timeLimitSeconds: mode === 'MOCK' ? 3600 : null,
    perQuestionSeconds: null,
    difficultyMix: { simple: 30, medium: 50, hard: 20 },
    shuffleQuestions: true,
    shuffleOptions: true,
    feedbackTiming: mode === 'PRACTICE' ? 'IMMEDIATE' : 'AFTER_SUBMIT',
    resultVisibility: 'FULL',
    negativeMark: 0,
    passMarkPct: null,
    allowTopicChoice: mode !== 'MOCK',
    adaptiveEnabled: false,
    maxRetakes: null,
    version: 0,
    saved: false,
    ...extra,
  };
}

function adminExam(extra: Record<string, unknown> = {}) {
  return {
    id: 'exam-1',
    title: 'Nursing Licensure Review',
    slug: 'nursing-licensure',
    categoryId: categoryNursing.id,
    shortDescription: 'Adult health.',
    coverUrl: null,
    featured: false,
    displayOrder: null,
    modes: [],
    status: 'DRAFT',
    publishedAt: null,
    updatedAt: '2026-10-11T08:00:00Z',
    version: 2,
    publishedQuestionCount: 3,
    trialQuestionCount: 0,
    topicCount: 1,
    publishReadiness: { ready: false, blockers: ['Enable at least one exam mode.'] },
    instructions: '',
    seoTitle: null,
    seoDescription: null,
    subjectIds: ['s1'],
    modeSettings: [modeSettings('PRACTICE'), modeSettings('MOCK'), modeSettings('USER_FRIENDLY')],
    ...extra,
  };
}

const library = [
  {
    id: 's1',
    title: 'Pharmacology',
    code: 'PHARM',
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
        ],
      },
    ],
  },
];

const page = (items: unknown[]) => jsonResponse({ items, page: { page: 0, size: 25, totalElements: items.length, totalPages: 1 } });

describe('admin exams', () => {
  it('lists exams with status and what still blocks publishing', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/exams': () =>
        page([
          adminExam(),
          adminExam({ id: 'exam-2', title: 'IELTS Academic', slug: 'ielts', status: 'PUBLISHED', modes: ['PRACTICE'], publishReadiness: { ready: true, blockers: [] } }),
        ]),
    });
    renderRoute('/admin/exams');
    const table = await screen.findByRole('table', { name: 'Exams, 2 in total' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Nursing Licensure Review');
    expect(rows[1]).toHaveTextContent('Draft');
    expect(rows[1]).toHaveTextContent('1 thing to fix');
    expect(rows[1]).toHaveTextContent('Nursing · /exams/nursing-licensure');
    expect(rows[2]).toHaveTextContent('Published');
    expect(rows[2]).toHaveTextContent('Live');
    expect(within(table).getByRole('link', { name: 'IELTS Academic' })).toHaveAttribute('href', '/admin/exams/exam-2');
  });

  it('creates a draft with an address made from the title', async () => {
    const posted: unknown[] = [];
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'POST /api/admin/catalog/exams': ({ body }) => {
        posted.push(body);
        return jsonResponse(adminExam({ id: 'new-1', title: 'IELTS Academic', slug: 'ielts-academic', version: 0 }), 201);
      },
    });
    renderRoute('/admin/exams/new');
    await userEvent.type(await screen.findByLabelText('Title'), 'IELTS Academic');
    expect(screen.getByLabelText('Address')).toHaveValue('ielts-academic');
    await screen.findByRole('option', { name: 'Nursing' });
    await userEvent.selectOptions(screen.getByLabelText('Category'), categoryNursing.id);
    await userEvent.click(await screen.findByLabelText('Pharmacology'));
    await userEvent.click(screen.getByRole('button', { name: 'Create draft' }));

    expect(await screen.findByText('Draft created. Now choose its modes, then publish when it’s ready.')).toBeInTheDocument();
    expect(posted[0]).toMatchObject({
      title: 'IELTS Academic',
      slug: 'ielts-academic',
      categoryId: categoryNursing.id,
      displayOrder: null,
      subjectIds: ['s1'],
    });
    expect(screen.getByRole('heading', { level: 1, name: 'IELTS Academic' })).toBeInTheDocument();
  });

  it('shows the field problems the server reports', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'POST /api/admin/catalog/exams': () =>
        jsonResponse({ type: 'about:blank', title: 'URL already in use', status: 409, code: 'SLUG_TAKEN', detail: 'The address is used by another exam.' }, 409),
    });
    renderRoute('/admin/exams/new');
    await userEvent.type(await screen.findByLabelText('Title'), 'IELTS');
    await screen.findByRole('option', { name: 'Nursing' });
    await userEvent.selectOptions(screen.getByLabelText('Category'), categoryNursing.id);
    await userEvent.click(screen.getByRole('button', { name: 'Create draft' }));
    expect(await screen.findByText('The address is used by another exam.')).toBeInTheDocument();
    expect(screen.getByLabelText('Address')).toHaveAttribute('aria-invalid', 'true');
  });

  it('validates the form before sending it', async () => {
    const fetchMock = mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
    });
    renderRoute('/admin/exams/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Create draft' }));
    expect(await screen.findByText('Give the exam a title (at least 2 characters).')).toBeInTheDocument();
    expect(screen.getByText('Choose a category.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'POST' && String(c[0]).includes('/exams'))).toBe(false);
  });

  it('lists blockers and only lets a ready exam be published', async () => {
    let current = adminExam();
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(current),
      'POST /api/admin/catalog/exams/exam-1/publish': () => {
        current = adminExam({ status: 'PUBLISHED', displayOrder: 3, modes: ['PRACTICE'], publishReadiness: { ready: true, blockers: [] } });
        return jsonResponse(current);
      },
    });
    const view = renderRoute('/admin/exams/exam-1');
    expect(await screen.findByText('Enable at least one exam mode.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeDisabled();
    view.unmount();

    current = adminExam({ modes: ['PRACTICE'], publishReadiness: { ready: true, blockers: [] } });
    renderRoute('/admin/exams/exam-1');
    expect(await screen.findByText('Ready to publish.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Publish' }));
    const dialog = await screen.findByRole('dialog', { name: 'Publish Nursing Licensure Review?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(await screen.findByText('Nursing Licensure Review is published.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View page/ })).toHaveAttribute('href', '/exams/nursing-licensure');
    expect(screen.getByText(/catalogue position 3/)).toBeInTheDocument();
  });

  it('shows why the server refused to publish', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(adminExam({ publishReadiness: { ready: true, blockers: [] } })),
      'POST /api/admin/catalog/exams/exam-1/publish': () =>
        jsonResponse(
          { type: 'about:blank', title: 'Cannot publish yet', status: 409, code: 'PUBLISH_BLOCKED', blockers: ['Practice needs at least 5 published questions; 3 published.'] },
          409,
        ),
    });
    renderRoute('/admin/exams/exam-1');
    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(await within(dialog).findByText('Practice needs at least 5 published questions; 3 published.')).toBeInTheDocument();
  });

  it('saves a mock exam’s time limit in seconds and keeps settings it doesn’t show', async () => {
    const saved: unknown[] = [];
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'GET /api/admin/catalog/exams/exam-1': () =>
        jsonResponse(adminExam({ modeSettings: [modeSettings('PRACTICE'), modeSettings('MOCK', { shuffleOptions: false }), modeSettings('USER_FRIENDLY')] })),
      'PUT /api/admin/catalog/exams/exam-1/modes/MOCK': ({ body }) => {
        saved.push(body);
        return jsonResponse(modeSettings('MOCK', { ...(body as object), saved: true, version: 1 }));
      },
    });
    renderRoute('/admin/exams/exam-1');
    const mock = await screen.findByRole('form', { name: 'Mock exam' });
    await userEvent.click(within(mock).getByLabelText('Offer mock exam for this exam'));
    const minutes = within(mock).getByLabelText('Time limit (minutes)');
    await userEvent.clear(minutes);
    await userEvent.type(minutes, '90');
    await userEvent.click(within(mock).getByRole('button', { name: 'Save mock exam settings' }));
    expect(await within(mock).findByText('Mock exam settings saved.')).toBeInTheDocument();
    expect(saved[0]).toMatchObject({ enabled: true, timeLimitSeconds: 5400, feedbackTiming: 'AFTER_SUBMIT', shuffleOptions: false });
  });

  it('checks question counts before saving a mode', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(adminExam()),
    });
    renderRoute('/admin/exams/exam-1');
    const practice = await screen.findByRole('form', { name: 'Practice' });
    const min = within(practice).getByLabelText('Minimum');
    await userEvent.clear(min);
    await userEvent.type(min, '20');
    await userEvent.click(within(practice).getByRole('button', { name: 'Save practice settings' }));
    expect(await within(practice).findByText('Must not be more than the default.')).toBeInTheDocument();
  });
});

describe('admin categories and content', () => {
  it('creates a category', async () => {
    const posted: unknown[] = [];
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
      'POST /api/admin/catalog/categories': ({ body }) => {
        posted.push(body);
        return jsonResponse({ ...adminCategory, id: 'c9', name: 'IT certifications', slug: 'it-certifications' }, 201);
      },
    });
    renderRoute('/admin/categories');
    expect(await screen.findByRole('table', { name: 'Categories' })).toHaveTextContent('1 published, 1 draft');
    await userEvent.click(screen.getByRole('button', { name: 'New category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Name'), 'IT certifications');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('IT certifications created.')).toBeInTheDocument();
    expect(posted[0]).toEqual({ name: 'IT certifications', slug: 'it-certifications', description: '', displayOrder: 0 });
  });

  it('shows the library and adds a topic to a module', async () => {
    const posted: unknown[] = [];
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/catalog/subjects': () => jsonResponse(library),
      'POST /api/admin/catalog/topics': ({ body }) => {
        posted.push(body);
        return jsonResponse({}, 201);
      },
    });
    renderRoute('/admin/content');
    expect(await screen.findByRole('heading', { name: /Pharmacology/ })).toBeInTheDocument();
    expect(screen.getByText('3 published questions')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add topic to Drug calculations' }));
    const dialog = await screen.findByRole('dialog', { name: 'New topic in Drug calculations' });
    await userEvent.type(within(dialog).getByLabelText('Title'), 'IV flow rates');
    await userEvent.click(within(dialog).getByLabelText('Dosage by weight'));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0]).toEqual({ moduleId: 'm1', title: 'IV flow rates', displayOrder: 0, prerequisiteTopicIds: ['t1'] });
    expect(await screen.findByText('IV flow rates added to Drug calculations.')).toBeInTheDocument();
  });

  it('keeps students out of catalogue administration', async () => {
    mockApi({ ...signedIn() });
    renderRoute('/admin/exams');
    expect(await screen.findByRole('heading', { level: 1, name: /not allowed|permission|access/i })).toBeInTheDocument();
  });
});
