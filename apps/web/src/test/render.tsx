import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '@/app/routes';

/** Renders the real route table at a URL, with a fresh query client that doesn't retry. */
export function renderRoute(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [url] });
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
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
