import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { categoryNursing, examDetail, examSummary, page } from '@/test/fixtures';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const FREE_ID = '0190d5e2-0000-7000-8000-000000000001';
const PREMIUM_ID = '0190d5e2-0000-7000-8000-000000000002';

const free = { access: 'FREE', reason: 'FA1_POSITION_1_OF_6', freeModes: ['PRACTICE'], welcomeAttemptsLeft: null };
const locked = { access: 'LOCKED', reason: 'SUBSCRIPTION_REQUIRED', freeModes: [], welcomeAttemptsLeft: null };

const catalogue = {
  'GET /api/categories': () => jsonResponse([categoryNursing]),
  'GET /api/exams': () =>
    jsonResponse(page([examSummary({ id: FREE_ID }), examSummary({ id: PREMIUM_ID, title: 'IELTS Academic', slug: 'ielts' })])),
};

describe('free and premium exams for students', () => {
  it('marks each catalogue card Free or Premium', async () => {
    const asked: string[] = [];
    mockApi({
      ...catalogue,
      'GET /api/access/exams': ({ url }) => {
        asked.push(url.searchParams.get('examIds') ?? '');
        return jsonResponse({ [FREE_ID]: free, [PREMIUM_ID]: locked });
      },
    });
    renderRoute('/exams');
    const premiumCard = (await screen.findByRole('link', { name: 'IELTS Academic' })).closest('li') as HTMLElement;
    expect(await within(premiumCard).findByText('Premium')).toBeInTheDocument();
    const freeCard = screen.getByRole('link', { name: 'Nursing Licensure Review' }).closest('li') as HTMLElement;
    expect(within(freeCard).getByText('Free')).toBeInTheDocument();
    expect(asked[0]?.split(',').sort()).toEqual([FREE_ID, PREMIUM_ID]);
  });

  it('shows a locked preview without any question content, and asks visitors to sign in', async () => {
    mockApi({
      'GET /api/exams/by-slug/ielts': () => jsonResponse(examDetail({ id: PREMIUM_ID, title: 'IELTS Academic', slug: 'ielts' })),
      'GET /api/access/exams': () => jsonResponse({ [PREMIUM_ID]: locked }),
    });
    renderRoute('/exams/ielts');
    const preview = await screen.findByRole('region', { name: 'This exam needs Premium access' });
    expect(within(preview).getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByText('Needs Premium access')).toBeInTheDocument();
  });

  it('tells a signed-in student how to get access and marks premium-only modes on free exams', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/ielts': () => jsonResponse(examDetail({ id: PREMIUM_ID, slug: 'ielts' })),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: FREE_ID })),
      'GET /api/access/exams': ({ url }) =>
        jsonResponse(url.searchParams.get('examIds') === FREE_ID ? { [FREE_ID]: free } : { [PREMIUM_ID]: locked }),
    });
    const view = renderRoute('/exams/ielts');
    expect(await screen.findByText(/an administrator can give your account Premium access/)).toBeInTheDocument();
    view.unmount();

    renderRoute('/exams/nursing-licensure');
    const mock = (await screen.findByRole('heading', { level: 3, name: /Mock exam/ })).closest('li') as HTMLElement;
    await waitFor(() => expect(within(mock).getByText('Premium')).toBeInTheDocument());
    const practice = screen.getByRole('heading', { level: 3, name: /Practice/ }).closest('li') as HTMLElement;
    expect(within(practice).queryByText('Premium')).not.toBeInTheDocument();
    expect(screen.getByText('Free: position 1 of the first 6 in the catalogue')).toBeInTheDocument();
  });

  it('shows the student’s plan on their account page', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/access/me': () =>
        jsonResponse({
          premium: true,
          entitlement: { source: 'MANUAL', endsAt: '2026-12-31T18:29:59Z' },
          welcomeAttemptsLeft: null,
          freeExamCount: 6,
        }),
    });
    renderRoute('/account');
    const plan = (await screen.findByRole('heading', { name: 'Your plan' })).closest('div') as HTMLElement;
    expect(await within(plan).findByText(/Every premium exam is open to you until/)).toBeInTheDocument();
  });
});

const admin = { id: 'admin-1', roles: ['ADMIN', 'STUDENT'], displayName: 'Kani Admin', email: 'kani@example.com' };

const policies = {
  policies: [
    {
      ruleCode: 'FA1_CATALOGUE_POSITION',
      active: true,
      params: { count: 6, scope: 'GLOBAL' },
      version: 2,
      updatedAt: '2026-10-25T08:00:00Z',
    },
    { ruleCode: 'FA2_WELCOME_ALLOWANCE', active: false, params: { attempts: 2 }, version: 0, updatedAt: '2026-10-25T08:00:00Z' },
    {
      ruleCode: 'FA4_FREE_FEATURES',
      active: true,
      params: { modes: ['PRACTICE', 'MOCK', 'USER_FRIENDLY'] },
      version: 0,
      updatedAt: '2026-10-25T08:00:00Z',
    },
  ],
  freeExamIds: ['e1'],
  exams: [
    {
      examId: 'e1',
      title: 'O/L Science',
      slug: 'ol-science',
      categoryId: 'c1',
      displayOrder: 1,
      access: 'FREE',
      reason: 'FA1_POSITION_1_OF_6',
    },
    {
      examId: 'e7',
      title: 'A/L Physics',
      slug: 'al-physics',
      categoryId: 'c1',
      displayOrder: 7,
      access: 'PREMIUM',
      reason: 'SUBSCRIPTION_REQUIRED',
    },
  ],
};

describe('admin access page', () => {
  it('lists which exams are free and saves a rule with its version', async () => {
    const saved: { body: unknown; ifMatch: string | null }[] = [];
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/policies': () => jsonResponse(policies),
      'GET /api/admin/billing/entitlements': () => jsonResponse([]),
      'GET /api/admin/users': () => jsonResponse({ items: [], page: { page: 0, size: 25, totalElements: 0, totalPages: 0 } }),
      'PUT /api/admin/billing/policies/FA1_CATALOGUE_POSITION': ({ body, init }) => {
        saved.push({ body, ifMatch: new Headers(init?.headers).get('If-Match') });
        return jsonResponse({ ...policies.policies[0], params: { count: 3, scope: 'CATEGORY' }, version: 3 });
      },
    });
    renderRoute('/admin/access');
    const table = await screen.findByRole('table', { name: 'Published exams and their access' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('O/L Science');
    expect(rows[1]).toHaveTextContent('Free: position 1 of the first 6 in the catalogue');
    expect(rows[2]).toHaveTextContent('Premium');

    const rule = screen.getByRole('form', { name: 'Free exams by catalogue position' });
    await userEvent.clear(within(rule).getByLabelText('How many'));
    await userEvent.type(within(rule).getByLabelText('How many'), '3');
    await userEvent.selectOptions(within(rule).getByLabelText('Counted'), 'CATEGORY');
    await userEvent.click(within(rule).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toEqual({ body: { active: true, params: { count: 3, scope: 'CATEGORY' } }, ifMatch: '"2"' });
  });

  it('gives a student Premium and revokes it with a reason', async () => {
    const granted: unknown[] = [];
    const revoked: unknown[] = [];
    const student = {
      id: 'u1',
      email: 'amaya@example.com',
      displayName: 'Amaya Perera',
      roles: ['STUDENT'],
      status: 'ACTIVE',
      version: 0,
    };
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/policies': () => jsonResponse(policies),
      'GET /api/admin/users': () =>
        jsonResponse({ items: [student], page: { page: 0, size: 25, totalElements: 1, totalPages: 1 } }),
      'GET /api/admin/users/names': () => jsonResponse([{ id: 'u1', displayName: 'Amaya Perera' }]),
      'GET /api/admin/billing/entitlements': () =>
        jsonResponse([
          {
            id: 'ent-1',
            studentId: 'u1',
            source: 'MANUAL',
            state: 'ACTIVE',
            startsAt: '2026-10-25T08:00:00Z',
            endsAt: '2026-11-24T18:29:59Z',
            grantReason: 'School trial',
            grantedBy: 'admin-1',
            revokedAt: null,
            revokedReason: null,
            createdAt: '2026-10-25T08:00:00Z',
          },
        ]),
      'POST /api/admin/billing/entitlements': ({ body }) => {
        granted.push(body);
        return jsonResponse({}, 201);
      },
      'POST /api/admin/billing/entitlements/ent-1/revoke': ({ body }) => {
        revoked.push(body);
        return new Response(null, { status: 204 });
      },
    });
    renderRoute('/admin/access');
    const form = await screen.findByRole('form', { name: 'Give Premium access' });
    await screen.findByRole('option', { name: 'Amaya Perera — amaya@example.com' });
    await userEvent.selectOptions(within(form).getByLabelText('Student'), 'u1');
    await userEvent.type(within(form).getByLabelText('Reason'), 'Scholarship winner');
    await userEvent.click(within(form).getByRole('button', { name: 'Give Premium' }));
    await waitFor(() => expect(granted).toHaveLength(1));
    expect(granted[0]).toMatchObject({ studentId: 'u1', reason: 'Scholarship winner' });

    const table = screen.getByRole('table', { name: 'Premium access' });
    expect(await within(table).findByText('Amaya Perera')).toBeInTheDocument();
    await userEvent.click(within(table).getByRole('button', { name: 'Revoke Premium for Amaya Perera' }));
    const dialog = await screen.findByRole('dialog', { name: 'Revoke Premium for Amaya Perera?' });
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Refunded');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }));
    await waitFor(() => expect(revoked).toEqual([{ reason: 'Refunded' }]));
  });
});
