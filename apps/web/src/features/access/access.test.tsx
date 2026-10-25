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

const mode = (m: string, access: string, freeAttempts: number, used: number | null = null) => ({
  mode: m,
  access,
  freeAttempts,
  used,
  left: used === null ? null : Math.max(0, freeAttempts - used),
});
const visitorView = {
  available: true,
  premium: false,
  modes: [mode('PRACTICE', 'FREE', 3), mode('MOCK', 'FREE', 1)],
};
const usedUp = {
  available: true,
  premium: false,
  modes: [mode('PRACTICE', 'LOCKED', 3, 3), mode('MOCK', 'LOCKED', 1, 1)],
};
const premiumOnly = { available: true, premium: false, modes: [mode('PRACTICE', 'LOCKED', 0), mode('MOCK', 'LOCKED', 0)] };

const catalogue = {
  'GET /api/categories': () => jsonResponse([categoryNursing]),
  'GET /api/exams': () =>
    jsonResponse(page([examSummary({ id: FREE_ID }), examSummary({ id: PREMIUM_ID, title: 'IELTS Academic', slug: 'ielts' })])),
};

describe('free attempts for students', () => {
  it('marks catalogue cards free to try or Premium', async () => {
    const asked: string[] = [];
    mockApi({
      ...catalogue,
      'GET /api/access/exams': ({ url }) => {
        asked.push(url.searchParams.get('examIds') ?? '');
        return jsonResponse({ [FREE_ID]: visitorView, [PREMIUM_ID]: premiumOnly });
      },
    });
    renderRoute('/exams');
    const premiumCard = (await screen.findByRole('link', { name: 'IELTS Academic' })).closest('li') as HTMLElement;
    expect(await within(premiumCard).findByText('Premium')).toBeInTheDocument();
    const freeCard = screen.getByRole('link', { name: 'Nursing Licensure Review' }).closest('li') as HTMLElement;
    expect(within(freeCard).getByText('Free to try')).toBeInTheDocument();
    expect(asked[0]?.split(',').sort()).toEqual([FREE_ID, PREMIUM_ID]);
  });

  it('shows visitors how many free attempts each exam type has and asks them to sign in to start', async () => {
    mockApi({
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: FREE_ID })),
      'GET /api/access/exams': () => jsonResponse({ [FREE_ID]: visitorView }),
    });
    renderRoute('/exams/nursing-licensure');
    const practice = (await screen.findByRole('heading', { level: 3, name: 'Practice' })).closest('li') as HTMLElement;
    expect(await within(practice).findByText('3 free attempts')).toBeInTheDocument();
    expect(within(practice).getByRole('button', { name: 'Sign in to start' })).toBeInTheDocument();
    const mock = screen.getByRole('heading', { level: 3, name: 'Mock exam' }).closest('li') as HTMLElement;
    expect(within(mock).getByText('1 free attempt')).toBeInTheDocument();
  });

  it('counts down a signed-in student’s free attempts and locks a used-up exam type until Premium', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: FREE_ID })),
      'GET /api/attempts': () => jsonResponse([]),
      'GET /api/access/exams': () =>
        jsonResponse({
          [FREE_ID]: { available: true, premium: false, modes: [mode('PRACTICE', 'FREE', 3, 1), mode('MOCK', 'LOCKED', 1, 1)] },
        }),
    });
    renderRoute('/exams/nursing-licensure');
    const practice = (await screen.findByRole('heading', { level: 3, name: 'Practice' })).closest('li') as HTMLElement;
    expect(await within(practice).findByText('2 of 3 free attempts left')).toBeInTheDocument();
    expect(within(practice).getByRole('link', { name: 'Start' })).toHaveAttribute('href', '/exams/nursing-licensure/start/practice');
    const mock = screen.getByRole('heading', { level: 3, name: 'Mock exam' }).closest('li') as HTMLElement;
    expect(within(mock).getByText('Free attempts used (1 of 1)')).toBeInTheDocument();
    expect(within(mock).getByRole('button', { name: 'Needs Premium' })).toBeDisabled();
    expect(within(mock).getByText('Premium')).toBeInTheDocument();
  });

  it('shows a locked preview, without question content, once every exam type is used up', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: FREE_ID })),
      'GET /api/attempts': () => jsonResponse([]),
      'GET /api/access/exams': () => jsonResponse({ [FREE_ID]: usedUp }),
    });
    renderRoute('/exams/nursing-licensure');
    expect(await screen.findByRole('region', { name: 'You’ve used your free attempts' })).toBeInTheDocument();
  });

  it('shows the student’s plan on their account page', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/access/me': () => jsonResponse({ premium: true, entitlement: { source: 'MANUAL', endsAt: '2026-12-31T18:29:59Z' } }),
    });
    renderRoute('/account');
    const plan = (await screen.findByRole('heading', { name: 'Your plan' })).closest('div') as HTMLElement;
    expect(await within(plan).findByText(/Unlimited attempts on every exam until/)).toBeInTheDocument();
  });
});

const admin = { id: 'admin-1', roles: ['ADMIN', 'STUDENT'], displayName: 'Kani Admin', email: 'kani@example.com' };

const freeList = [
  { examId: 'e1', title: 'O/L Science', slug: 'ol-science', modes: ['PRACTICE', 'MOCK'], freeAttempts: { PRACTICE: 3, MOCK: 0 } },
];

describe('admin access page', () => {
  it('lists each published exam’s free attempts per exam type, linking to where they are set', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/billing/free-attempts': () => jsonResponse(freeList),
      'GET /api/admin/billing/entitlements': () => jsonResponse([]),
      'GET /api/admin/users': () => jsonResponse({ items: [], page: { page: 0, size: 25, totalElements: 0, totalPages: 0 } }),
    });
    renderRoute('/admin/access');
    const table = await screen.findByRole('table', { name: 'Published exams and their free attempts' });
    const row = within(table).getAllByRole('row')[1] as HTMLElement;
    expect(within(row).getByRole('link', { name: 'O/L Science' })).toHaveAttribute('href', '/admin/exams/e1/modes');
    expect(row).toHaveTextContent('3 free');
    expect(row).toHaveTextContent('Premium only');
    expect(row).toHaveTextContent('Off');
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
      'GET /api/admin/billing/free-attempts': () => jsonResponse(freeList),
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
