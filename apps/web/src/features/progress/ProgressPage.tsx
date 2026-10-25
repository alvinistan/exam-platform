import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ArrowDown, ArrowUp, ChartNoAxesColumn, Target } from 'lucide-react';
import { Badge, Card, EmptyState, ErrorState, SelectField, Skeleton, buttonClasses } from '@/components/ui';
import type { BadgeTone } from '@/components/ui';
import { modeLabels, useExamBySlug } from '@/features/exams/api';
import { startPath } from '@/features/attempts/modes';
import { cn } from '@/lib/cn';
import {
  statusLabels,
  useExamProgress,
  useExamsPractised,
  type ExamPractised,
  type TopicProgress,
  type TopicStatus,
} from './api';

const statusTone: Record<TopicStatus, BadgeTone> = {
  STRONG: 'free',
  DEVELOPING: 'primary',
  NEEDS_WORK: 'warning',
  NOT_ENOUGH: 'neutral',
};

const shortDate = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' });

/**
 * /progress — the student's performance profile (ADR-016): per exam, each topic's accuracy, current level (Easy,
 * Medium, Hard) and status, what to practise next, and recent results. Everything comes from marked answers.
 */
export function ProgressPage() {
  const exams = useExamsPractised();
  const [params, setParams] = useSearchParams();

  if (exams.isPending) {
    return (
      <Shell>
        <p role="status" className="sr-only">
          Loading your progress…
        </p>
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-72 w-full" />
      </Shell>
    );
  }
  if (exams.isError) {
    return (
      <Shell>
        <ErrorState title="We couldn’t load your progress" onRetry={() => void exams.refetch()} />
      </Shell>
    );
  }
  if (exams.data.length === 0) {
    return (
      <Shell>
        <EmptyState
          icon={ChartNoAxesColumn}
          title="Your progress appears here"
          action={
            <Link to="/exams" className={buttonClasses('primary')}>
              Browse exams
            </Link>
          }
        >
          <p>Answer some questions in any exam and you’ll see how you’re doing on each topic.</p>
        </EmptyState>
      </Shell>
    );
  }
  const selected = exams.data.find((e) => e.examId === params.get('exam')) ?? exams.data[0];
  return (
    <Shell>
      {exams.data.length > 1 && (
        <div className="max-w-sm">
          <SelectField
            label="Exam"
            value={selected?.examId ?? ''}
            onChange={(e) => setParams({ exam: e.target.value }, { replace: true })}
          >
            {exams.data.map((e) => (
              <option key={e.examId} value={e.examId}>
                {e.title}
              </option>
            ))}
          </SelectField>
        </div>
      )}
      {selected && <ExamProgressView exam={selected} />}
    </Shell>
  );
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1100px] flex-col gap-6 px-4 py-8 sm:px-8 sm:py-10">
      <title>My progress · Leenex Labs</title>
      <header className="flex flex-col gap-1">
        <h1 className="text-3xl font-extrabold tracking-tight">My progress</h1>
        <p className="text-muted">
          How you’re doing on each topic. Questions are chosen at your level, and it moves as you improve.
        </p>
      </header>
      {children}
    </div>
  );
}

function ExamProgressView({ exam }: { exam: ExamPractised }) {
  const progress = useExamProgress(exam.examId);
  const detail = useExamBySlug(exam.slug);
  if (progress.isPending) return <Skeleton className="h-72 w-full" />;
  if (progress.isError)
    return <ErrorState title="We couldn’t load this exam’s progress" onRetry={() => void progress.refetch()} />;
  const p = progress.data;
  const focus = p.focus.map((id) => p.topics.find((t) => t.topicId === id)).filter((t): t is TopicProgress => !!t);
  // Practise weak topics in the Adaptive exam when the exam offers it, otherwise in Practice.
  const modes = detail.data?.modes ?? [];
  const practiseMode = modes.includes('USER_FRIENDLY') ? 'USER_FRIENDLY' : modes.includes('PRACTICE') ? 'PRACTICE' : null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-2xl font-bold">{exam.title}</h2>
        <Link to={`/exams/${exam.slug}`} className="font-semibold">
          Go to the exam
        </Link>
      </div>

      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Figure label="Questions answered" value={p.answered} />
        <Figure label="Correct overall" value={`${p.accuracy}%`} />
        <Figure label="Topics practised" value={p.topics.length} />
        <Figure label="Need work" value={focus.length} />
      </dl>

      {p.answered === 0 ? (
        <Card className="p-6">
          <p className="text-ink-2">No marked answers yet. Answers in a mock exam count once the paper is finished.</p>
        </Card>
      ) : (
        <>
          {focus.length > 0 && (
            <Card className="flex flex-col gap-4 border-[#f2d8a6] bg-warning-soft/40 p-5 sm:p-6">
              <div className="flex items-center gap-2">
                <Target aria-hidden="true" className="size-5 text-warning-ink" />
                <h3 className="text-lg font-bold">Practise next</h3>
              </div>
              <ul className="flex flex-wrap gap-2">
                {focus.map((t) => (
                  <li
                    key={t.topicId}
                    className="rounded-full bg-surface px-3.5 py-1.5 text-[14.5px] font-semibold ring-1 ring-[#f2d8a6]"
                  >
                    {t.title} <span className="font-normal text-muted">· {t.recentAccuracy}% lately</span>
                  </li>
                ))}
              </ul>
              {practiseMode && (
                <div>
                  <Link
                    to={`${startPath(exam.slug, practiseMode)}?topics=${focus.map((t) => t.topicId).join(',')}`}
                    className={buttonClasses('primary')}
                  >
                    Practise these topics ({modeLabels[practiseMode].toLowerCase()})
                  </Link>
                </div>
              )}
            </Card>
          )}

          <section aria-labelledby="topics-heading" className="flex flex-col gap-3">
            <h3 id="topics-heading" className="text-xl font-bold">
              Topics
            </h3>
            <Card className="overflow-x-auto">
              <table className="w-full min-w-[640px] border-collapse text-[14.5px]">
                <caption className="sr-only">Your level and accuracy on each topic</caption>
                <thead>
                  <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                    <th scope="col" className="px-4 py-3">
                      Topic
                    </th>
                    <th scope="col" className="px-4 py-3">
                      Your level
                    </th>
                    <th scope="col" className="px-4 py-3">
                      Last 8 answers
                    </th>
                    <th scope="col" className="px-4 py-3">
                      All answers
                    </th>
                    <th scope="col" className="px-4 py-3">
                      Status
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {p.topics.map((t) => (
                    <tr key={t.topicId} className="border-b border-border last:border-b-0">
                      <th scope="row" className="px-4 py-3 text-left font-semibold">
                        {t.title}
                        <span className="block text-[12.5px] font-normal text-muted">
                          Last practised {shortDate.format(new Date(t.lastPracticed))}
                        </span>
                      </th>
                      <td className="px-4 py-3">
                        <LevelPill topic={t} />
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-3">
                          <div
                            role="meter"
                            aria-label={`${t.title}, last answers: ${t.recentAccuracy}% correct`}
                            aria-valuemin={0}
                            aria-valuemax={100}
                            aria-valuenow={t.recentAccuracy}
                            className="h-2.5 w-28 overflow-hidden rounded-full bg-surface-2"
                          >
                            <div
                              className={cn(
                                'h-full rounded-full',
                                t.recentAccuracy >= 80 ? 'bg-success' : t.recentAccuracy < 50 ? 'bg-warning' : 'bg-primary',
                              )}
                              style={{ width: `${t.recentAccuracy}%` }}
                            />
                          </div>
                          <span className="tabular-nums">{t.recentAccuracy}%</span>
                        </div>
                      </td>
                      <td className="px-4 py-3 tabular-nums text-ink-2">
                        {t.correct} / {t.answered}
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={statusTone[t.status]}>{statusLabels[t.status]}</Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
            <p className="text-[13.5px] text-muted">
              Levels move one step at a time: up after 80% or more correct, down below 40%, judged on at least 5 answers since the
              last change.
            </p>
          </section>

          {p.sessions.length > 0 && <Sessions sessions={p.sessions} />}
        </>
      )}
    </div>
  );
}

function Figure({ label, value }: { label: string; value: number | string }) {
  return (
    <Card className="flex flex-col gap-1 p-4">
      <dt className="text-[13.5px] text-muted">{label}</dt>
      <dd className="text-2xl font-extrabold tabular-nums">{value}</dd>
    </Card>
  );
}

function LevelPill({ topic }: { topic: TopicProgress }) {
  const filled = topic.level;
  return (
    <span className="inline-flex items-center gap-2">
      <span aria-hidden="true" className="flex gap-0.5">
        {[1, 2, 3].map((n) => (
          <span key={n} className={cn('h-3.5 w-2 rounded-sm', n <= filled ? 'bg-primary' : 'bg-surface-2')} />
        ))}
      </span>
      <span className="font-semibold">{topic.levelLabel}</span>
      {topic.levelTrend > 0 && (
        <span className="inline-flex items-center text-[12.5px] text-success-ink">
          <ArrowUp aria-hidden="true" className="size-3.5" />
          up
        </span>
      )}
      {topic.levelTrend < 0 && (
        <span className="inline-flex items-center text-[12.5px] text-warning-ink">
          <ArrowDown aria-hidden="true" className="size-3.5" />
          down
        </span>
      )}
    </span>
  );
}

function Sessions({
  sessions,
}: {
  sessions: { attemptId: string; mode: keyof typeof modeLabels; finishedAt: string; percent: number }[];
}) {
  return (
    <section aria-labelledby="sessions-heading" className="flex flex-col gap-3">
      <h3 id="sessions-heading" className="text-xl font-bold">
        Recent results
      </h3>
      <Card className="p-5">
        <ol className="flex h-40 items-end gap-2 overflow-x-auto" aria-label="Score of each finished session, oldest first">
          {sessions.map((s) => (
            <li key={s.attemptId} className="flex h-full min-w-9 flex-1 flex-col items-center justify-end gap-1">
              <span className="text-[12px] font-semibold tabular-nums">{s.percent}%</span>
              <Link
                to={`/attempts/${s.attemptId}`}
                aria-label={`${modeLabels[s.mode]} on ${shortDate.format(new Date(s.finishedAt))}: ${s.percent}%`}
                className="block w-full max-w-10 rounded-t-sm bg-primary/80 hover:bg-primary"
                style={{ height: `${Math.max(4, s.percent)}%` }}
              />
              <span className="text-[11px] text-muted">{shortDate.format(new Date(s.finishedAt))}</span>
            </li>
          ))}
        </ol>
      </Card>
    </section>
  );
}
