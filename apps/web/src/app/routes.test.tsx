import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute } from '@/test/render';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

describe('routing', () => {
  it('renders the home page with the brand and study modes', async () => {
    const fetchMock = mockApi({});
    renderRoute('/');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Practise smarter');
    expect(screen.getByRole('link', { name: 'Leenex Labs' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('heading', { name: 'Mock exam' })).toBeInTheDocument();
    await screen.findByRole('button', { name: 'Sign in' });
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual(['/api/auth/token']);
  });

  it('shows a not-found page for unknown addresses', () => {
    mockApi({});
    renderRoute('/no/such/page');
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
  });

  it('offers a skip link to the main content', () => {
    mockApi({});
    renderRoute('/');
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main');
  });
});

describe('dynamic exam route', () => {
  it('renders any published exam from its slug, with no exam-specific page', async () => {
    const fetchMock = mockApi({
      'GET /api/exams/by-slug/nursing-licensure': () =>
        jsonResponse({
          id: '0190d5e2-0000-7000-8000-000000000001',
          title: 'Nursing Licensure Review',
          slug: 'nursing-licensure',
          categoryId: '0190d5e2-0000-7000-8000-000000000002',
          shortDescription: 'Adult health, pharmacology and safe practice.',
          modes: ['PRACTICE', 'MOCK'],
        }),
    });
    renderRoute('/exams/nursing-licensure');

    expect(await screen.findByRole('heading', { level: 1, name: 'Nursing Licensure Review' })).toBeInTheDocument();
    expect(screen.getByText('Adult health, pharmacology and safe practice.')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Available modes' })).toHaveTextContent('PracticeMock exam');
    expect(fetchMock.mock.calls.map((c) => c[0])).toContain('/api/exams/by-slug/nursing-licensure');
  });

  it('shows a loading status first', () => {
    mockApi({ 'GET /api/exams/by-slug/ielts-academic': () => new Promise<Response>(() => undefined) });
    renderRoute('/exams/ielts-academic');
    expect(screen.getByText('Loading exam…')).toBeInTheDocument();
  });

  it('shows "Exam not found" when the catalogue has no such published exam', async () => {
    mockApi({});
    renderRoute('/exams/retired-exam');
    expect(await screen.findByRole('heading', { level: 1, name: 'Exam not found' })).toBeInTheDocument();
  });

  it('shows an error with a support reference and lets the student retry', async () => {
    let calls = 0;
    mockApi({
      'GET /api/exams/by-slug/ielts-academic': () =>
        ++calls === 1
          ? jsonResponse({ type: 'about:blank', title: 'Something went wrong', status: 500, code: 'INTERNAL_ERROR', correlationId: 'corr-9' }, 500)
          : jsonResponse({ id: 'x', title: 'IELTS Academic', slug: 'ielts-academic', categoryId: 'c', modes: [] }),
    });
    renderRoute('/exams/ielts-academic');
    expect(await screen.findByText("We couldn't load this exam")).toBeInTheDocument();
    expect(screen.getByText('Reference: corr-9')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'IELTS Academic' })).toBeInTheDocument();
  });
});
