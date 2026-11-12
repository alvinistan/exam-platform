import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { examDetail } from '@/test/fixtures';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const days = Array.from({ length: 14 }, (_, i) => ({
  day: `2026-10-${String(13 + i).padStart(2, '0')}`,
  answered: i === 13 ? 12 : i % 3 === 0 ? 5 : 0,
  correct: i === 13 ? 9 : i % 3 === 0 ? 3 : 0,
}));

const overview = {
  answered: 64,
  correct: 41,
  accuracy: 64,
  finishedAttempts: 6,
  streakDays: 3,
  answeredThisWeek: 22,
  activity: days,
  focus: [
    {
      examId: 'e1',
      examTitle: 'Nursing Licensure Review',
      examSlug: 'nursing-licensure',
      topicId: 't2',
      title: 'IV flow rates',
      level: 1,
      levelLabel: 'Easy',
      recentAccuracy: 25,
    },
  ],
  exams: [
    {
      examId: 'e1',
      title: 'Nursing Licensure Review',
      slug: 'nursing-licensure',
      lastActivity: new Date().toISOString(),
      attempts: 6,
      answered: 64,
      accuracy: 64,
      topicsNeedingWork: 1,
    },
  ],
};

const summary = (id: string, extra: Record<string, unknown>) => ({
  id,
  examId: 'e1',
  examTitle: 'Nursing Licensure Review',
  examSlug: 'nursing-licensure',
  mode: 'PRACTICE',
  status: 'SCORED',
  finishReason: 'SUBMITTED',
  startedAt: '2026-10-26T04:00:00Z',
  expiresAt: null,
  finishedAt: '2026-10-26T04:10:00Z',
  questionCount: 10,
  score: 7,
  maxScore: 10,
  percent: 70,
  correct: 7,
  incorrect: 3,
  unanswered: 0,
  ...extra,
});

describe('student dashboard', () => {
  it('shows unfinished attempts, totals, what to practise next, activity, exams and recent results', async () => {
    const asked: string[] = [];
    mockApi({
      ...signedIn(),
      'GET /api/progress/overview': ({ url }) => {
        asked.push(url.searchParams.get('tz') ?? '');
        return jsonResponse(overview);
      },
      'GET /api/attempts': () =>
        jsonResponse([
          summary('open-1', {
            mode: 'MOCK',
            status: 'IN_PROGRESS',
            finishReason: null,
            finishedAt: null,
            expiresAt: '2026-10-26T05:00:00Z',
          }),
          summary('done-1', {}),
        ]),
      'GET /api/access/me': () => jsonResponse({ premium: false, entitlement: null }),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: 'e1' })),
    });
    renderRoute('/dashboard');
    expect(await screen.findByRole('heading', { level: 1, name: /Amaya$/ })).toBeInTheDocument();
    expect(asked[0]).toBeTruthy();

    const open = await screen.findByRole('region', { name: 'Continue where you left off' });
    expect(within(open).getByText('Mock exam')).toBeInTheDocument();
    expect(within(open).getByRole('link', { name: /Continue/ })).toHaveAttribute('href', '/attempts/open-1');

    expect(screen.getByText('Day streak').closest('div')).toHaveTextContent('3');
    expect(screen.getByText('Correct overall').closest('div')).toHaveTextContent('64%');

    const next = screen.getByRole('region', { name: 'Practise next' });
    expect(within(next).getByText('IV flow rates')).toBeInTheDocument();
    // The fixture exam has no Adaptive exam, so weak topics go to Practice.
    expect(await within(next).findByRole('link', { name: /Practise this topic/ })).toHaveAttribute(
      'href',
      '/exams/nursing-licensure/start/practice?topics=t2',
    );

    const activity = screen.getByRole('region', { name: 'Questions answered, last 14 days' });
    const today = within(activity).getByRole('button', { name: /12 answered, 9 correct/ });
    await userEvent.hover(today);
    expect(within(activity).getByRole('tooltip')).toHaveTextContent('12 answered · 9 correct');
    expect(within(activity).getByRole('table', { hidden: true })).toBeInTheDocument();

    expect(screen.getByRole('region', { name: 'Your exams' })).toHaveTextContent('1 to revise');
    expect(screen.getByRole('region', { name: 'Recent results' })).toHaveTextContent('70%');
    expect(screen.getByText('Free plan')).toBeInTheDocument();
  });

  it('guides a new student to their first exam', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/progress/overview': () =>
        jsonResponse({ ...overview, answered: 0, correct: 0, accuracy: 0, streakDays: 0, focus: [], exams: [] }),
      'GET /api/attempts': () => jsonResponse([]),
      'GET /api/access/me': () =>
        jsonResponse({ premium: true, entitlement: { source: 'MANUAL', endsAt: '2026-12-31T00:00:00Z' } }),
    });
    renderRoute('/dashboard');
    expect(await screen.findByRole('heading', { name: 'Start your first exam' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Browse exams/ })).toHaveAttribute('href', '/exams');
    expect(screen.getByText('Premium')).toBeInTheDocument();
  });

  it('sends signed-in visitors from the home page to their dashboard', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/progress/overview': () => jsonResponse({ ...overview, answered: 0, focus: [], exams: [] }),
      'GET /api/attempts': () => jsonResponse([]),
      'GET /api/access/me': () => jsonResponse({ premium: false, entitlement: null }),
    });
    renderRoute('/');
    expect(await screen.findByRole('heading', { name: 'Start your first exam' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
  });
});
