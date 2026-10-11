import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, meResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { clearSession } from './session';
import * as session from './session';

vi.mock('./session', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./session')>();
  return { ...actual, startSignIn: vi.fn() };
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.mocked(session.startSignIn).mockClear();
  clearSession();
});

describe('signed out', () => {
  it('offers "Sign in", which goes to Google and returns to the current page', async () => {
    mockApi({});
    renderRoute('/exams/ielts-academic?mode=mock');
    await userEvent.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(session.startSignIn).toHaveBeenCalledOnce();
  });

  it('sends visitors to sign-in from pages that need an account, remembering where they were', async () => {
    mockApi({});
    renderRoute('/account');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in to Leenex Labs' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Continue with Google' }));
    expect(session.startSignIn).toHaveBeenCalledWith('/account');
  });

  it('explains why a sign-in failed', async () => {
    mockApi({});
    renderRoute('/signin?error=suspended');
    expect(await screen.findByRole('alert')).toHaveTextContent('Your account is suspended');
  });

  it('never returns to another site after sign-in', async () => {
    mockApi({});
    renderRoute('/signin?returnTo=//evil.example');
    await userEvent.click(await screen.findByRole('button', { name: 'Continue with Google' }));
    expect(session.startSignIn).toHaveBeenCalledWith('/');
  });
});

describe('signed in', () => {
  it('restores the session on load and shows the account menu', async () => {
    mockApi(signedIn());
    renderRoute('/');
    const menuButton = await screen.findByRole('button', { name: /Amaya Perera/ });
    await userEvent.click(menuButton);
    expect(menuButton).toHaveAttribute('aria-expanded', 'true');
    const nav = screen.getByRole('navigation', { name: 'Account' });
    expect(within(nav).getByRole('link', { name: 'Your account' })).toHaveAttribute('href', '/account');
    expect(within(nav).queryByRole('link', { name: 'Administration' })).not.toBeInTheDocument();
  });

  it('closes the account menu with Escape', async () => {
    mockApi(signedIn());
    renderRoute('/');
    const menuButton = await screen.findByRole('button', { name: /Amaya Perera/ });
    await userEvent.click(menuButton);
    await userEvent.keyboard('{Escape}');
    expect(menuButton).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows Administration only to administrators', async () => {
    mockApi(signedIn({ roles: ['ADMIN', 'STUDENT'] }));
    renderRoute('/');
    await userEvent.click(await screen.findByRole('button', { name: /Amaya Perera/ }));
    expect(screen.getByRole('link', { name: 'Administration' })).toHaveAttribute('href', '/admin/users');
  });

  it('signs out on the server and in the page', async () => {
    const fetchMock = mockApi({ ...signedIn(), 'POST /api/auth/logout': () => new Response(null, { status: 204 }) });
    renderRoute('/');
    await userEvent.click(await screen.findByRole('button', { name: /Amaya Perera/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    const logout = fetchMock.mock.calls.find((c) => c[0] === '/api/auth/logout');
    expect(new Headers(logout?.[1]?.headers).get('X-Requested-With')).toBe('fetch');
  });

  it('keeps students out of administration pages', async () => {
    mockApi(signedIn());
    renderRoute('/admin/users');
    expect(await screen.findByRole('heading', { level: 1, name: "You don't have access to this page" })).toBeInTheDocument();
  });
});

describe('account page', () => {
  it('saves the display name with the version it was read at', async () => {
    const fetchMock = mockApi({
      ...signedIn(),
      'PATCH /api/users/me': ({ body }) => meResponse({ ...(body as object), version: 4 }),
    });
    renderRoute('/account');
    const name = await screen.findByLabelText('Display name');
    await userEvent.clear(name);
    await userEvent.type(name, 'Amaya P.');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Profile saved')).toBeInTheDocument();
    const patch = fetchMock.mock.calls.find((c) => c[1]?.method === 'PATCH');
    expect(new Headers(patch?.[1]?.headers).get('If-Match')).toBe('"3"');
    expect(patch?.[1]?.body).toBe('{"displayName":"Amaya P."}');
  });

  it('validates the name before sending it', async () => {
    const fetchMock = mockApi(signedIn());
    renderRoute('/account');
    const name = await screen.findByLabelText('Display name');
    await userEvent.clear(name);
    await userEvent.type(name, '   ');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Enter the name to show on your results.')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'PATCH')).toBe(false);
  });

  it('explains an edit conflict from another window', async () => {
    mockApi({
      ...signedIn(),
      'PATCH /api/users/me': () => jsonResponse({ type: 'about:blank', title: 'Conflict', status: 409, code: 'CONFLICT' }, 409),
    });
    renderRoute('/account');
    const name = await screen.findByLabelText('Display name');
    await userEvent.type(name, ' Jr');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/changed in another window/)).toBeInTheDocument();
  });

  it('deletes the account only after confirmation', async () => {
    const fetchMock = mockApi({
      ...signedIn(),
      'DELETE /api/users/me': () => new Response(null, { status: 202 }),
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    });
    renderRoute('/account');
    await userEvent.click(await screen.findByRole('button', { name: 'Delete my account' }));
    expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'DELETE')).toBe(false);
    await userEvent.click(screen.getByRole('button', { name: 'Delete account' }));
    await waitFor(() => expect(fetchMock.mock.calls.some((c) => c[1]?.method === 'DELETE')).toBe(true));
    expect(await screen.findByRole('heading', { level: 1, name: /Practise smarter/ })).toBeInTheDocument();
  });
});
