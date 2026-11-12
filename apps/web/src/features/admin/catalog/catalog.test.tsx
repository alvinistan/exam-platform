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
