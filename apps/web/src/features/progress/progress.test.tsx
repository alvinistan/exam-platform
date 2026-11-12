import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { jsonResponse, mockApi, renderRoute, signedIn } from '@/test/render';
import { examDetail } from '@/test/fixtures';
import { clearSession } from '@/lib/auth/session';

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

const exams = [
  {
    examId: 'e1',
    title: 'O/L Science Revision',
    slug: 'nursing-licensure',
    lastActivity: '2026-10-26T08:00:00Z',
    attempts: 3,
    answered: 24,
    accuracy: 63,
    topicsNeedingWork: 1,
  },
  {
    examId: 'e2',
    title: 'A/L Physics',
    slug: 'al-physics',
    lastActivity: '2026-10-20T08:00:00Z',
    attempts: 1,
    answered: 0,
    accuracy: 0,
    topicsNeedingWork: 0,
  },
];

const topic = (topicId: string, title: string, extra: Record<string, unknown>) => ({
  topicId,
  title,
  answered: 8,
  correct: 4,
  accuracy: 50,
  recentAccuracy: 50,
  level: 2,
  levelLabel: 'Medium',
  status: 'DEVELOPING',
  levelTrend: 0,
  lastPracticed: '2026-10-26T08:00:00Z',
  ...extra,
});

const science = {
  examId: 'e1',
  answered: 24,
  correct: 15,
  accuracy: 63,
  topics: [
    topic('t1', 'Electricity', { recentAccuracy: 25, level: 1, levelLabel: 'Easy', status: 'NEEDS_WORK', levelTrend: -1 }),
    topic('t2', 'Human Body', { recentAccuracy: 100, level: 3, levelLabel: 'Hard', status: 'STRONG', levelTrend: 1 }),
  ],
  focus: ['t1'],
  sessions: [
    { attemptId: 'a1', mode: 'PRACTICE', finishedAt: '2026-10-25T08:00:00Z', percent: 50, questions: 10 },
    { attemptId: 'a2', mode: 'USER_FRIENDLY', finishedAt: '2026-10-26T08:00:00Z', percent: 70, questions: 10 },
  ],
};

describe('my progress', () => {
  it('shows each topic’s level and status, and links weak topics to a practice session', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/progress/exams': () => jsonResponse(exams),
      'GET /api/progress/exams/e1': () => jsonResponse(science),
      'GET /api/progress/exams/e2': () =>
        jsonResponse({ ...science, examId: 'e2', answered: 0, topics: [], focus: [], sessions: [] }),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: 'e1' })),
      'GET /api/exams/by-slug/al-physics': () => jsonResponse(examDetail({ id: 'e2', slug: 'al-physics' })),
    });
    renderRoute('/progress');
    expect(await screen.findByRole('heading', { level: 2, name: 'O/L Science Revision' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Your level and accuracy on each topic' });
    const electricity = within(table).getByRole('row', { name: /Electricity/ });
    expect(electricity).toHaveTextContent('Easy');
    expect(electricity).toHaveTextContent('down');
    expect(electricity).toHaveTextContent('Needs work');
    expect(within(table).getByRole('row', { name: /Human Body/ })).toHaveTextContent('Strong');

    // The fixture exam offers Practice and Mock, not the Adaptive exam, so weak topics go to Practice.
    expect(await screen.findByRole('link', { name: /Practise these topics/ })).toHaveAttribute(
      'href',
      '/exams/nursing-licensure/start/practice?topics=t1',
    );
    expect(screen.getByRole('link', { name: /^Practice on .+: 50%$/ })).toHaveAttribute('href', '/attempts/a1');

    await userEvent.selectOptions(screen.getByLabelText('Exam'), 'e2');
    expect(await screen.findByText(/No marked answers yet/)).toBeInTheDocument();
  });

  it('explains what will appear before the first answer', async () => {
    mockApi({ ...signedIn(), 'GET /api/progress/exams': () => jsonResponse([]) });
    renderRoute('/progress');
    expect(await screen.findByText('Your progress appears here')).toBeInTheDocument();
  });

  it('preselects the topics a progress link asks for on the start page', async () => {
    mockApi({
      ...signedIn(),
      'GET /api/exams/by-slug/nursing-licensure': () => jsonResponse(examDetail({ id: 'e1' })),
      'GET /api/access/exams': () => jsonResponse({}),
    });
    renderRoute('/exams/nursing-licensure/start/practice?topics=t2');
    expect(await screen.findByText('1 of 2 topics chosen.')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'IV flow rates' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Dosage by weight' })).not.toBeChecked();
  });
});
