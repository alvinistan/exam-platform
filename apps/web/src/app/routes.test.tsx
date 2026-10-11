import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, renderRoute } from '@/test/render';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

describe('routing', () => {
  it('renders the home page with the brand and study modes', () => {
    renderRoute('/');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Practise smarter');
    expect(screen.getByRole('link', { name: 'Leenex Labs' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('heading', { name: 'Mock exam' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows a not-found page for unknown addresses', () => {
    renderRoute('/no/such/page');
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
  });

  it('offers a skip link to the main content', () => {
    renderRoute('/');
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main');
  });
});

describe('dynamic exam route', () => {
  it('renders any published exam from its slug, with no exam-specific page', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({
        id: '0190d5e2-0000-7000-8000-000000000001',
        title: 'Nursing Licensure Review',
        slug: 'nursing-licensure',
        categoryId: '0190d5e2-0000-7000-8000-000000000002',
        shortDescription: 'Adult health, pharmacology and safe practice.',
        modes: ['PRACTICE', 'MOCK'],
      }),
    );
    renderRoute('/exams/nursing-licensure');

    expect(await screen.findByRole('heading', { level: 1, name: 'Nursing Licensure Review' })).toBeInTheDocument();
    expect(screen.getByText('Adult health, pharmacology and safe practice.')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Available modes' })).toHaveTextContent('PracticeMock exam');
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/exams/by-slug/nursing-licensure');
  });

  it('shows a loading status first', () => {
    fetchMock.mockReturnValue(new Promise(() => undefined));
    renderRoute('/exams/ielts-academic');
    expect(screen.getByRole('status')).toHaveTextContent('Loading exam');
  });

  it('shows "Exam not found" when the catalogue has no such published exam', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ type: 'about:blank', title: 'Not found', status: 404, code: 'NOT_FOUND' }, 404),
    );
    renderRoute('/exams/retired-exam');
    expect(await screen.findByRole('heading', { level: 1, name: 'Exam not found' })).toBeInTheDocument();
  });

  it('shows an error with a support reference and lets the student retry', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(
        { type: 'about:blank', title: 'Something went wrong', status: 500, code: 'INTERNAL_ERROR', correlationId: 'corr-9' },
        500,
      ),
    );
    renderRoute('/exams/ielts-academic');
    expect(await screen.findByText("We couldn't load this exam")).toBeInTheDocument();
    expect(screen.getByText('Reference: corr-9')).toBeInTheDocument();

    fetchMock.mockResolvedValueOnce(
      jsonResponse({ id: 'x', title: 'IELTS Academic', slug: 'ielts-academic', categoryId: 'c', modes: [] }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'IELTS Academic' })).toBeInTheDocument();
  });
});
