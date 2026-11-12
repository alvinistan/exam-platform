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
    freeAttempts: mode === 'MOCK' ? 1 : 3,
    version: 0,
    saved: false,
    ...extra,
  };
}

const notReady = (...issues: [string, string][]) => ({
  ready: false,
  blockers: issues.map(([, m]) => m),
  issues: issues.map(([step, message]) => ({ step, message })),
});
const ready = { ready: true, blockers: [], issues: [] };

function adminExam(extra: Record<string, unknown> = {}) {
  return {
    id: 'exam-1',
    title: 'O/L Science Revision',
    slug: 'ol-science',
    categoryId: categoryNursing.id,
    shortDescription: 'Practise O/L Science by topic.',
    coverUrl: null,
    featured: false,
    displayOrder: null,
    modes: [],
    status: 'DRAFT',
    publishedAt: null,
    updatedAt: '2026-10-25T08:00:00Z',
    version: 2,
    publishedQuestionCount: 0,
    trialQuestionCount: 0,
    topicCount: 0,
    draftQuestionCount: 0,
    subjectCount: 0,
    publishReadiness: notReady(['MODES', 'Turn on at least one mode.'], ['STRUCTURE', 'Add a subject with at least one topic.']),
    instructions: '',
    seoTitle: null,
    seoDescription: null,
    subjectIds: [],
    modeSettings: [modeSettings('PRACTICE'), modeSettings('MOCK'), modeSettings('USER_FRIENDLY')],
    ...extra,
  };
}

const science = {
  id: 's1',
  title: 'Science',
  code: null,
  description: null,
  status: 'ACTIVE',
  version: 0,
  linkedExamCount: 1,
  modules: [
    {
      id: 'm1',
      subjectId: 's1',
      title: 'Physics',
      displayOrder: 1,
      status: 'ACTIVE',
      version: 0,
      topics: [
        {
          id: 't1',
          moduleId: 'm1',
          title: 'Electricity',
          displayOrder: 1,
          status: 'ACTIVE',
          version: 3,
          prerequisiteTopicIds: [],
          publishedQuestionCount: 4,
          trialQuestionCount: 0,
        },
      ],
    },
  ],
};

const withStructure = {
  subjectIds: ['s1'],
  subjectCount: 1,
  topicCount: 1,
  publishReadiness: notReady(['MODES', 'Turn on at least one mode.']),
};

const page = (items: unknown[]) =>
  jsonResponse({ items, page: { page: 0, size: 25, totalElements: items.length, totalPages: 1 } });

function question(id: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    bank: 'MAIN',
    status: 'DRAFT',
    topicId: 't1',
    topicTitle: 'Electricity',
    moduleTitle: 'Physics',
    subjectTitle: 'Science',
    difficulty: 'SIMPLE',
    answerType: 'SINGLE',
    stemText: `Question ${id}`,
    versionNo: 1,
    hasDraftChanges: false,
    tags: [],
    updatedAt: '2026-10-25T08:00:00Z',
    updatedBy: 'admin-1',
    ...extra,
  };
}

const base = {
  ...signedIn(admin),
  'GET /api/admin/catalog/categories': () => jsonResponse([adminCategory]),
  'GET /api/admin/catalog/subjects': () => jsonResponse([science]),
};

describe('exams list', () => {
  it('shows each exam’s setup progress and the next thing to do', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams': () =>
        page([
          adminExam({ ...withStructure }),
          adminExam({
            id: 'exam-2',
            title: 'A/L Physics',
            slug: 'al-physics',
            status: 'PUBLISHED',
            modes: ['PRACTICE'],
            publishedQuestionCount: 80,
            publishReadiness: ready,
          }),
          adminExam({ id: 'exam-3', title: 'Old exam', slug: 'old', status: 'ARCHIVED' }),
        ]),
    });
    renderRoute('/admin/exams');
    const draft = await screen.findByRole('group', { name: 'O/L Science Revision' });
    expect(draft).toHaveTextContent('Being set up');
    expect(draft).toHaveTextContent('Step 3 of 5 · Questions');
    expect(draft).toHaveTextContent('2 of 4 done');
    expect(within(draft).getByRole('progressbar', { name: 'O/L Science Revision setup' })).toHaveAttribute('aria-valuenow', '2');
    expect(within(draft).getByRole('link', { name: 'Continue setup: O/L Science Revision' })).toHaveAttribute(
      'href',
      '/admin/exams/exam-1/questions',
    );

    const live = screen.getByRole('group', { name: 'A/L Physics' });
    expect(live).toHaveTextContent('Live');
    expect(live).toHaveTextContent('80');
    expect(within(live).getByRole('link', { name: /View as student/ })).toHaveAttribute('href', '/exams/al-physics');

    const archived = screen.getByRole('group', { name: 'Old exam' });
    expect(within(archived).getByRole('button', { name: 'Restore to draft: Old exam' })).toBeInTheDocument();
  });

  it('restores an archived exam from the list', async () => {
    const restored: string[] = [];
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams': () => page([adminExam({ status: 'ARCHIVED' })]),
      'POST /api/admin/catalog/exams/exam-1/restore': () => {
        restored.push('exam-1');
        return jsonResponse(adminExam());
      },
    });
    renderRoute('/admin/exams');
    await userEvent.click(await screen.findByRole('button', { name: 'Restore to draft: O/L Science Revision' }));
    await waitFor(() => expect(restored).toEqual(['exam-1']));
  });

  it('creates the sample exam and opens its last step', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams': () => page([]),
      'POST /api/admin/catalog/sample-exams/ol-science': () =>
        jsonResponse(
          adminExam({
            id: 'sample-1',
            title: 'O/L Science Revision (sample)',
            ...withStructure,
            modes: ['PRACTICE', 'MOCK'],
            publishedQuestionCount: 24,
            publishReadiness: ready,
          }),
          201,
        ),
      'GET /api/admin/catalog/exams/sample-1': () =>
        jsonResponse(
          adminExam({
            id: 'sample-1',
            title: 'O/L Science Revision (sample)',
            ...withStructure,
            modes: ['PRACTICE', 'MOCK'],
            publishedQuestionCount: 24,
            publishReadiness: ready,
          }),
        ),
    });
    renderRoute('/admin/exams');
    await userEvent.click(await screen.findByRole('button', { name: 'Create sample exam (O/L Science)' }));
    expect(await screen.findByText(/Sample exam created with 24 ready questions/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Ready to publish' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish exam' })).toBeEnabled();
  });
});

describe('step 1: details', () => {
  it('creates a draft with an address made from the title, then opens step 2', async () => {
    const posted: unknown[] = [];
    mockApi({
      ...base,
      'POST /api/admin/catalog/exams': ({ body }) => {
        posted.push(body);
        return jsonResponse(adminExam({ id: 'new-1', version: 0 }), 201);
      },
      'GET /api/admin/catalog/exams/new-1': () => jsonResponse(adminExam({ id: 'new-1', version: 0 })),
    });
    renderRoute('/admin/exams/new');
    const steps = await screen.findByRole('navigation', { name: 'Setup steps' });
    expect(within(steps).getAllByText('After step 1')).toHaveLength(4);
    await userEvent.type(await screen.findByLabelText('Exam title'), 'O/L Science Revision');
    expect(screen.getByLabelText('Web address')).toHaveValue('ol-science-revision');
    await screen.findByRole('option', { name: 'Nursing' });
    await userEvent.selectOptions(screen.getByLabelText('Category'), categoryNursing.id);
    await userEvent.click(screen.getByRole('button', { name: 'Create draft and continue' }));

    expect(await screen.findByText('Draft created. Next, add what the exam covers.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'What does this exam cover?' })).toBeInTheDocument();
    expect(posted[0]).toMatchObject({
      title: 'O/L Science Revision',
      slug: 'ol-science-revision',
      categoryId: categoryNursing.id,
      subjectIds: [],
      displayOrder: null,
    });
  });

  it('adds a category without leaving the page', async () => {
    const categories = [adminCategory];
    mockApi({
      ...base,
      'GET /api/admin/catalog/categories': () => jsonResponse(categories),
      'POST /api/admin/catalog/categories': ({ body }) => {
        const created = { ...adminCategory, id: 'c-ol', name: 'G.C.E. Ordinary Level', slug: (body as { slug: string }).slug };
        categories.push(created);
        return jsonResponse(created, 201);
      },
    });
    renderRoute('/admin/exams/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Add a new category' }));
    const dialog = await screen.findByRole('dialog', { name: 'New category' });
    await userEvent.type(within(dialog).getByLabelText('Category name'), 'G.C.E. Ordinary Level');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(screen.getByLabelText('Category')).toHaveValue('c-ol'));
  });

  it('validates before sending and shows the server’s address problem', async () => {
    const fetchMock = mockApi({
      ...base,
      'POST /api/admin/catalog/exams': () =>
        jsonResponse(
          {
            type: 'about:blank',
            title: 'URL already in use',
            status: 409,
            code: 'SLUG_TAKEN',
            detail: 'The address is used by another exam.',
          },
          409,
        ),
    });
    renderRoute('/admin/exams/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Create draft and continue' }));
    expect(await screen.findByText('Give the exam a title (at least 2 characters).')).toBeInTheDocument();
    expect(screen.getByText('Choose a category, or add one.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'POST' && String(c[0]).includes('/exams'))).toBe(false);

    await userEvent.type(screen.getByLabelText('Exam title'), 'IELTS');
    await userEvent.selectOptions(screen.getByLabelText('Category'), categoryNursing.id);
    await userEvent.click(screen.getByRole('button', { name: 'Create draft and continue' }));
    expect(await screen.findByText('The address is used by another exam.')).toBeInTheDocument();
    expect(screen.getByLabelText('Web address')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('setup navigation', () => {
  it('opens the first step that still needs work and marks each step’s state', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(adminExam({ ...withStructure, publishedQuestionCount: 4 })),
    });
    renderRoute('/admin/exams/exam-1');
    expect(await screen.findByRole('heading', { level: 2, name: 'Exam types' })).toBeInTheDocument();
    const steps = screen.getByRole('navigation', { name: 'Setup steps' });
    expect(within(steps).getByRole('link', { name: /Subjects & topics/ })).toHaveTextContent('Done:');
    expect(within(steps).getByRole('link', { name: /Exam types/ })).toHaveAttribute('aria-current', 'step');
    expect(within(steps).getByRole('link', { name: /Exam types/ })).toHaveTextContent('Needs attention:');
  });

  it('offers to restore an archived exam', async () => {
    let current = adminExam({ status: 'ARCHIVED', ...withStructure });
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(current),
      'POST /api/admin/catalog/exams/exam-1/restore': () => {
        current = adminExam({ ...withStructure });
        return jsonResponse(current);
      },
    });
    renderRoute('/admin/exams/exam-1/publish');
    expect(await screen.findByText('This exam is archived')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Restore to draft' }));
    expect(await screen.findByText(/is a draft again/)).toBeInTheDocument();
  });
});

describe('step 2: subjects and topics', () => {
  it('adds a subject, a topic, and archives a topic', async () => {
    const calls: { method: string; path: string; body: unknown; ifMatch?: string | null }[] = [];
    const record =
      (method: string, path: string, response: unknown, status = 200) =>
      ({ body, init }: { body: unknown; init: RequestInit | undefined }) => {
        calls.push({ method, path, body, ifMatch: new Headers(init?.headers).get('If-Match') });
        return jsonResponse(response, status);
      };
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(adminExam({ ...withStructure })),
      'POST /api/admin/catalog/exams/exam-1/subjects': record(
        'POST',
        'subjects',
        adminExam({ ...withStructure, subjectIds: ['s1', 's2'] }),
      ),
      'POST /api/admin/catalog/topics': record('POST', 'topics', {}, 201),
      'PUT /api/admin/catalog/topics/t1': record('PUT', 'topic', {}),
    });
    renderRoute('/admin/exams/exam-1/subjects');
    const subject = await screen.findByRole('region', { name: /Science/ });
    expect(within(subject).getByRole('list', { name: 'Topics in Science' })).toHaveTextContent('Electricity4 ready');

    await userEvent.type(within(subject).getByLabelText('New topic in Science'), 'Motion');
    await userEvent.click(within(subject).getByRole('button', { name: 'Add topic' }));
    expect(await screen.findByText('Motion added to Science.')).toBeInTheDocument();

    await userEvent.click(within(subject).getByRole('button', { name: 'Archive topic Electricity' }));
    expect(await screen.findByText('Electricity archived.')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('New subject name'), 'Mathematics');
    await userEvent.click(screen.getByRole('button', { name: 'Add subject' }));
    expect(await screen.findByText('Mathematics added.')).toBeInTheDocument();

    expect(calls).toEqual([
      {
        method: 'POST',
        path: 'topics',
        body: { subjectId: 's1', title: 'Motion', displayOrder: 2, prerequisiteTopicIds: [] },
        ifMatch: null,
      },
      { method: 'PUT', path: 'topic', body: { title: 'Electricity', displayOrder: 1, status: 'ARCHIVED' }, ifMatch: '"3"' },
      { method: 'POST', path: 'subjects', body: { title: 'Mathematics' }, ifMatch: null },
    ]);
  });

  it('explains when a change would stop a live exam from working', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () =>
        jsonResponse(adminExam({ ...withStructure, status: 'PUBLISHED', publishReadiness: ready })),
      'PUT /api/admin/catalog/topics/t1': () =>
        jsonResponse(
          {
            type: 'about:blank',
            title: 'Blocked',
            status: 409,
            code: 'PUBLISH_BLOCKED',
            detail: 'This change would leave the published exam unable to run.',
            blockers: ['Add a subject with at least one topic.'],
          },
          409,
        ),
    });
    renderRoute('/admin/exams/exam-1/subjects');
    await userEvent.click(await screen.findByRole('button', { name: 'Archive topic Electricity' }));
    expect(await screen.findByText('That would stop the live exam from working')).toBeInTheDocument();
    expect(screen.getByText('Add a subject with at least one topic.')).toBeInTheDocument();
  });
});

describe('step 3: questions', () => {
  it('marks drafts ready, and lists any that still need fixing', async () => {
    const bulk: string[][] = [];
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () =>
        jsonResponse(adminExam({ ...withStructure, publishedQuestionCount: 1, draftQuestionCount: 2 })),
      'GET /api/admin/catalog/questions': ({ url }) => {
        const status = url.searchParams.get('status');
        if (status === 'DRAFT') return page([question('q1'), question('q2')]);
        if (status === 'REVIEWED') return page([]);
        return page([question('q1'), question('q2'), question('q3', { status: 'PUBLISHED' })]);
      },
      'POST /api/admin/catalog/questions/bulk': ({ body }) => {
        const ids = (body as { questionIds: string[] }).questionIds;
        bulk.push(ids);
        if (ids.includes('q2')) {
          return jsonResponse(
            {
              type: 'about:blank',
              title: 'Invalid',
              status: 400,
              code: 'VALIDATION_FAILED',
              errors: [{ field: 'q2', message: 'Fix these first. Add 4 or 5 options (there are 2).' }],
            },
            400,
          );
        }
        return jsonResponse({ updated: ids.length, createdIds: [] });
      },
    });
    renderRoute('/admin/exams/exam-1/questions');
    expect(await screen.findByRole('table', { name: /Questions in this exam/ })).toHaveTextContent('Question q3');
    expect(screen.getByRole('link', { name: 'Question q1' })).toHaveAttribute(
      'href',
      '/admin/questions/q1?returnTo=%2Fadmin%2Fexams%2Fexam-1%2Fquestions',
    );
    expect(screen.getByRole('link', { name: /Write a question/ })).toHaveAttribute(
      'href',
      '/admin/questions/new?examId=exam-1&returnTo=%2Fadmin%2Fexams%2Fexam-1%2Fquestions',
    );

    await userEvent.click(screen.getByRole('button', { name: 'Mark all 2 as ready' }));
    expect(await screen.findByText('1 question is now ready.')).toBeInTheDocument();
    expect(screen.getByText(/1 still need fixing/)).toBeInTheDocument();
    expect(screen.getByText(/Add 4 or 5 options/)).toBeInTheDocument();
    expect(bulk).toEqual([['q1', 'q2'], ['q1']]);
  });
});

describe('step 4: exam types', () => {
  it('saves the most questions, free attempts, the mock time limit and feedback, keeping settings it doesn’t show', async () => {
    const saved: unknown[] = [];
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () =>
        jsonResponse(
          adminExam({
            ...withStructure,
            modeSettings: [
              modeSettings('PRACTICE'),
              modeSettings('MOCK', { shuffleOptions: false }),
              modeSettings('USER_FRIENDLY'),
            ],
          }),
        ),
      'PUT /api/admin/catalog/exams/exam-1/modes/MOCK': ({ body }) => {
        saved.push(body);
        return jsonResponse(modeSettings('MOCK', { ...(body as object), saved: true, version: 1 }));
      },
    });
    renderRoute('/admin/exams/exam-1/modes');
    const mock = await screen.findByRole('form', { name: 'Mock exam' });
    await userEvent.click(within(mock).getByLabelText('Offer the mock exam for this exam'));
    const most = within(mock).getByLabelText('Most questions in one session');
    await userEvent.clear(most);
    await userEvent.type(most, '30');
    const free = within(mock).getByLabelText('Free attempts per student');
    await userEvent.clear(free);
    await userEvent.type(free, '2');
    const minutes = within(mock).getByLabelText('Longest mock exam (minutes)');
    await userEvent.clear(minutes);
    await userEvent.type(minutes, '90');
    await userEvent.selectOptions(within(mock).getByLabelText('When students see the answers'), 'IMMEDIATE');
    await userEvent.click(within(mock).getByRole('button', { name: 'Save mock exam settings' }));
    expect(await within(mock).findByText('Mock exam settings saved.')).toBeInTheDocument();
    expect(saved[0]).toMatchObject({
      enabled: true,
      maxCount: 30,
      minCount: 1,
      defaultCount: 10,
      freeAttempts: 2,
      timeLimitSeconds: 5400,
      feedbackTiming: 'IMMEDIATE',
      shuffleOptions: false,
    });
    // Practice and Adaptive always show answers straight away, so they have no feedback setting.
    expect(within(screen.getByRole('form', { name: 'Practice' })).queryByLabelText('When students see the answers')).toBeNull();
  });
});

describe('step 5: review and publish', () => {
  it('links each problem to the step that fixes it, and only publishes a ready exam', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () =>
        jsonResponse(
          adminExam({
            ...withStructure,
            publishReadiness: notReady(['QUESTIONS', 'Practice needs at least 5 ready questions; 0 ready.']),
          }),
        ),
    });
    renderRoute('/admin/exams/exam-1/publish');
    expect(await screen.findByText('Practice needs at least 5 ready questions; 0 ready.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Fix Questions' })).toHaveAttribute('href', '/admin/exams/exam-1/questions');
    expect(screen.getByRole('link', { name: 'Go to the first thing to fix' })).toHaveAttribute(
      'href',
      '/admin/exams/exam-1/questions',
    );
    expect(screen.getByRole('button', { name: 'Publish exam' })).toBeDisabled();
  });

  it('publishes after confirmation', async () => {
    let current = adminExam({ ...withStructure, modes: ['PRACTICE'], publishedQuestionCount: 6, publishReadiness: ready });
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () => jsonResponse(current),
      'POST /api/admin/catalog/exams/exam-1/publish': () => {
        current = adminExam({
          ...withStructure,
          modes: ['PRACTICE'],
          publishedQuestionCount: 6,
          publishReadiness: ready,
          status: 'PUBLISHED',
          displayOrder: 3,
        });
        return jsonResponse(current);
      },
    });
    renderRoute('/admin/exams/exam-1/publish');
    await userEvent.click(await screen.findByRole('button', { name: 'Publish exam' }));
    const dialog = await screen.findByRole('dialog', { name: 'Publish O/L Science Revision?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(
      await screen.findByText('O/L Science Revision is live. Students can find it at /exams/ol-science.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View as a student/ })).toHaveAttribute('href', '/exams/ol-science');
  });

  it('shows why the server refused to publish', async () => {
    mockApi({
      ...base,
      'GET /api/admin/catalog/exams/exam-1': () =>
        jsonResponse(adminExam({ ...withStructure, modes: ['PRACTICE'], publishReadiness: ready })),
      'POST /api/admin/catalog/exams/exam-1/publish': () =>
        jsonResponse(
          {
            type: 'about:blank',
            title: 'Cannot publish yet',
            status: 409,
            code: 'PUBLISH_BLOCKED',
            blockers: ['Practice needs at least 5 ready questions; 3 ready.'],
          },
          409,
        ),
    });
    renderRoute('/admin/exams/exam-1/publish');
    await userEvent.click(await screen.findByRole('button', { name: 'Publish exam' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Publish' }));
    expect(await within(dialog).findByText('Practice needs at least 5 ready questions; 3 ready.')).toBeInTheDocument();
  });
});

describe('access', () => {
  it('keeps students out of exam setup', async () => {
    mockApi({ ...signedIn() });
    renderRoute('/admin/exams/exam-1/details');
    expect(await screen.findByRole('heading', { level: 1, name: /not allowed|permission|access/i })).toBeInTheDocument();
  });
});
