import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute } from '@/test/render';
import { categoryIt, categoryNursing, examDetail, examSummary, page } from '@/test/fixtures';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const problem = (status: number, code: string) => jsonResponse({ type: 'about:blank', title: code, status, code }, status);

describe('exam page', () => {
  it('shows modes, instructions and what is covered, all from the catalogue', async () => {
    mockApi({ 'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail()) });
    renderRoute('/exams/nursing-licensure');

    expect(await screen.findByRole('heading', { level: 1, name: 'Nursing Licensure Review' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Nursing' })).toHaveAttribute('href', '/exams?category=nursing');
    expect(screen.getByText('See the answer after each question')).toBeInTheDocument();
    expect(screen.getByText('Up to 75 questions, or choose how many minutes you have')).toBeInTheDocument();
    expect(screen.getByText('Timed, up to 1 h 30 min')).toBeInTheDocument();
    expect(screen.getByText('Pass mark 60%')).toBeInTheDocument();
    expect(screen.getByText('Wrong answers lose 0.25 of a mark')).toBeInTheDocument();
    expect(screen.getByText(/Some questions have more than one answer/)).toBeInTheDocument();
    expect(screen.getByText('IV flow rates')).toBeInTheDocument();
    expect(within(screen.getByRole('complementary', { name: 'Exam summary' })).getByText('2')).toBeInTheDocument();
    // Visitors are asked to sign in before starting.
    expect(screen.getAllByRole('button', { name: 'Sign in to start' })).toHaveLength(2);
    await waitFor(() => expect(document.title).toBe('Nursing Licensure Review · Leenex Labs'));
  });

  it('tells students when an exam exists but is not available', async () => {
    mockApi({ 'GET /api/exams/by-slug/old-exam': () => problem(404, 'EXAM_UNAVAILABLE') });
    renderRoute('/exams/old-exam');
    expect(await screen.findByRole('heading', { level: 1, name: 'This exam isn’t available' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse exams' })).toHaveAttribute('href', '/exams');
  });

  it('moves a renamed exam’s old address to the new one', async () => {
    const fetchMock = mockApi({
      // fetch follows the server's 301, so the old address answers with the exam under its new slug.
      'GET /api/exams/by-slug/nursing-review': () => jsonResponse(examDetail()),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail()),
    });
    renderRoute('/exams/nursing-review');
    expect(await screen.findByRole('heading', { level: 1, name: 'Nursing Licensure Review' })).toBeInTheDocument();
    await waitFor(() =>
      expect(fetchMock.mock.calls.map((c) => c[0])).toContain('/api/exams/by-slug/nursing-licensure'),
    );
  });
});

describe('catalogue', () => {
  it('lists published exams with their category and filters by search and category', async () => {
    const fetchMock = mockApi({
      'GET /api/categories': () => jsonResponse([categoryNursing, categoryIt]),
      'GET /api/exams': ({ url }) =>
        jsonResponse(
          url.searchParams.get('q') === 'nothing'
            ? page([])
            : page([examSummary(), examSummary({ id: 'e2', title: 'AWS Cloud Practitioner', slug: 'aws-cloud', categoryId: categoryIt.id, modes: ['USER_FRIENDLY'] })]),
        ),
    });
    renderRoute('/exams');

    const nursing = await screen.findByRole('link', { name: 'Nursing Licensure Review' });
    expect(nursing).toHaveAttribute('href', '/exams/nursing-licensure');
    expect(screen.getByText('2 exams')).toBeInTheDocument();
    expect(screen.getAllByText('IT certifications').length).toBeGreaterThan(0);
    expect(screen.getByText('Adaptive exam')).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Category'), 'it-certifications');
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('category=it-certifications'))).toBe(true),
    );

    await userEvent.type(screen.getByLabelText('Search exams'), 'nothing');
    await userEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(await screen.findByRole('heading', { name: 'No exams match' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    expect(await screen.findByRole('link', { name: 'Nursing Licensure Review' })).toBeInTheDocument();
  });

  it('explains an empty catalogue', async () => {
    mockApi({ 'GET /api/categories': () => jsonResponse([]), 'GET /api/exams': () => jsonResponse(page([])) });
    renderRoute('/exams');
    expect(await screen.findByRole('heading', { name: 'No exams yet' })).toBeInTheDocument();
  });
});

describe('home page', () => {
  it('shows featured exams and categories from the catalogue API', async () => {
    const fetchMock = mockApi({
      'GET /api/categories': () => jsonResponse([categoryNursing, categoryIt]),
      'GET /api/exams': () => jsonResponse(page([examSummary()])),
    });
    renderRoute('/');
    expect(await screen.findByRole('heading', { name: 'Featured exams' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Nursing Licensure Review' })).toBeInTheDocument();
    // Only categories that have published exams are offered.
    expect(await screen.findByRole('link', { name: /Nursing\s*1/ })).toHaveAttribute('href', '/exams?category=nursing');
    expect(screen.queryByRole('link', { name: /IT certifications/ })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('featured=true'))).toBe(true);
  });

  it('leaves the section out when nothing is published', async () => {
    mockApi({ 'GET /api/categories': () => jsonResponse([]), 'GET /api/exams': () => jsonResponse(page([])) });
    renderRoute('/');
    await screen.findByRole('button', { name: 'Sign in' });
    await waitFor(() => expect(screen.queryByText('Loading featured exams…')).not.toBeInTheDocument());
    expect(screen.queryByRole('heading', { name: 'Featured exams' })).not.toBeInTheDocument();
  });
});
