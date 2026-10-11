import type { ReactElement } from 'react';
import { vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '@/app/routes';
import { AuthProvider } from '@/lib/auth/AuthProvider';

/** Renders the real route table at a URL, with a fresh query client that doesn't retry. */
export function renderRoute(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [url] });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

export function renderUi(ui: ReactElement) {
  return render(ui);
}

/** A fetch Response with a JSON body. */
export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}) {
  const contentType = status >= 400 ? 'application/problem+json' : 'application/json';
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType, ...headers } });
}

type Handler = (request: { url: URL; init: RequestInit | undefined; body: unknown }) => Response | Promise<Response>;

/**
 * A fake API for tests: routes "METHOD /path" to handlers. By default the visitor is signed out (token refresh
 * answers 401) and anything else is a 404 problem. Returns the fetch mock so tests can inspect calls.
 */
export function mockApi(routes: Record<string, Handler>) {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url, 'http://localhost');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
    const handler = routes[`${method} ${url.pathname}`];
    if (handler) return handler({ url, init, body });
    if (method === 'POST' && url.pathname === '/api/auth/token') {
      return jsonResponse({ type: 'about:blank', title: 'Sign-in required', status: 401, code: 'UNAUTHENTICATED' }, 401);
    }
    return jsonResponse({ type: 'about:blank', title: 'Not found', status: 404, code: 'NOT_FOUND' }, 404);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

export const tokenOk = () => jsonResponse({ accessToken: 'access-1', expiresIn: 900, tokenType: 'Bearer' });

export function meResponse(overrides: Record<string, unknown> = {}) {
  return jsonResponse({
    id: '0190d5e2-0000-7000-8000-00000000aa01',
    email: 'amaya@example.com',
    displayName: 'Amaya Perera',
    photoUrl: null,
    roles: ['STUDENT'],
    status: 'ACTIVE',
    preferences: {},
    version: 3,
    ...overrides,
  });
}

/** Routes for a signed-in student (or admin, with roles override). */
export function signedIn(overrides: Record<string, unknown> = {}): Record<string, Handler> {
  return {
    'POST /api/auth/token': () => tokenOk(),
    'GET /api/users/me': () => meResponse(overrides),
  };
}
