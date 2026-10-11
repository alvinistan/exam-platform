import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const admin = { id: 'admin-1', roles: ['ADMIN', 'STUDENT'], displayName: 'Kani Admin', email: 'kani@example.com' };

function user(id: string, displayName: string, extra: Record<string, unknown> = {}) {
  return {
    id,
    email: `${id}@example.com`,
    displayName,
    photoUrl: null,
    roles: ['STUDENT'],
    status: 'ACTIVE',
    preferences: {},
    lastLoginAt: '2026-10-10T08:00:00Z',
    version: 1,
    ...extra,
  };
}

function page(items: unknown[]) {
  return jsonResponse({ items, page: { page: 0, size: 25, totalElements: items.length, totalPages: 1 } });
}

describe('admin users page', () => {
  it('lists users with their access and status, without actions on the admin themselves', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/users': () =>
        page([user('admin-1', 'Kani Admin', { roles: ['ADMIN', 'STUDENT'] }), user('u-2', 'Nimal Silva', { status: 'SUSPENDED' })]),
    });
    renderRoute('/admin/users');
    const table = await screen.findByRole('table', { name: 'Users, 2 in total' });
    const rows = within(table).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Kani Admin (you)');
    expect(within(rows[1] as HTMLElement).queryByRole('button')).not.toBeInTheDocument();
    expect(rows[2]).toHaveTextContent('Suspended');
    expect(within(rows[2] as HTMLElement).getByRole('button', { name: 'Reactivate Nimal Silva' })).toBeInTheDocument();
    expect(screen.getByText('Showing 1–2 of 2')).toBeInTheDocument();
  });

  it('searches as the admin types', async () => {
    const fetchMock = mockApi({ ...signedIn(admin), 'GET /api/admin/users': () => page([]) });
    renderRoute('/admin/users');
    await userEvent.type(await screen.findByLabelText('Search users'), 'nimal');
    await waitFor(() =>
      expect(fetchMock.mock.calls.map((c) => String(c[0]))).toContainEqual(expect.stringContaining('q=nimal')),
    );
    expect(await screen.findByRole('heading', { name: 'No users match' })).toBeInTheDocument();
  });

  it('suspends a user only with a reason, then confirms it', async () => {
    let suspended = false;
    const fetchMock = mockApi({
      ...signedIn(admin),
      'GET /api/admin/users': () => page([user('u-2', 'Nimal Silva', suspended ? { status: 'SUSPENDED' } : {})]),
      'PUT /api/admin/users/u-2/status': () => {
        suspended = true;
        return new Response(null, { status: 204 });
      },
    });
    renderRoute('/admin/users');
    await userEvent.click(await screen.findByRole('button', { name: 'Suspend Nimal Silva' }));
    const dialog = screen.getByRole('dialog', { name: 'Suspend Nimal Silva?' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Suspend' }));
    expect(await within(dialog).findByText('Give a short reason (at least 3 characters).')).toBeInTheDocument();

    await userEvent.type(within(dialog).getByLabelText('Reason'), 'Shared account');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Suspend' }));

    expect(await screen.findByText('Nimal Silva is suspended and signed out everywhere.')).toBeInTheDocument();
    const put = fetchMock.mock.calls.find((c) => c[1]?.method === 'PUT');
    expect(put?.[1]?.body).toBe('{"status":"SUSPENDED","reason":"Shared account"}');
    expect(await screen.findByRole('button', { name: 'Reactivate Nimal Silva' })).toBeInTheDocument();
  });

  it('shows the server’s reason when a change is refused', async () => {
    mockApi({
      ...signedIn(admin),
      'GET /api/admin/users': () => page([user('u-3', 'Other Admin', { roles: ['ADMIN', 'STUDENT'] })]),
      'DELETE /api/admin/users/u-3/roles/ADMIN': () =>
        jsonResponse(
          { type: 'about:blank', title: 'Conflict', status: 409, code: 'CONFLICT', detail: 'The last administrator can’t be removed.' },
          409,
        ),
    });
    renderRoute('/admin/users');
    await userEvent.click(await screen.findByRole('button', { name: 'Remove admin for Other Admin' }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Remove admin' }));
    expect(await within(dialog).findByText('The last administrator can’t be removed.')).toBeInTheDocument();
  });
});
