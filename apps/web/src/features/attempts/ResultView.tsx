import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { CircleCheck, CircleMinus, CircleX, Clock, RotateCcw } from 'lucide-react';
import { Alert, Badge, Card, buttonClasses } from '@/components/ui';
import { modeLabels } from '@/features/exams/api';
import { formatDuration } from '@/features/exams/format';
import { QuestionView } from '@/features/questions/QuestionView';
import { cn } from '@/lib/cn';
import type { Attempt, AttemptQuestion, TopicResult } from './api';
import { difficultyLabels, startPath } from './modes';
import { toViewQuestion } from './hooks';

type Filter = 'ALL' | 'INCORRECT' | 'UNANSWERED' | 'CORRECT';

const filterLabels: Record<Filter, string> = {
  ALL: 'All',
  INCORRECT: 'Incorrect',
  UNANSWERED: 'Not answered',
  CORRECT: 'Correct',
};

const outcome = (q: AttemptQuestion): Exclude<Filter, 'ALL'> =>
  q.review?.correct === true ? 'CORRECT' : q.review?.correct === false ? 'INCORRECT' : 'UNANSWERED';

/** Topics under this share correct are marked for revision. */
const WEAK_BELOW = 50;

/** The result of a finished attempt: score, per-topic breakdown, and every question with its answer and explanation. */
export function ResultView({ attempt }: { attempt: Attempt }) {
  const result = attempt.result;
  const [filter, setFilter] = useState<Filter>('ALL');
  if (!result) return null;
  const shown = attempt.questions.filter((q) => filter === 'ALL' || outcome(q) === filter);
  const counts: Record<Filter, number> = {
    ALL: attempt.questions.length,
    INCORRECT: result.incorrect,
    UNANSWERED: result.unanswered,
    CORRECT: result.correct,
  };
  const weak = result.byTopic.filter((t) => t.percent < WEAK_BELOW);

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 px-4 py-8 sm:px-8 sm:py-10">
      <title>{`Result · ${attempt.examTitle} · Leenex Labs`}</title>
      <header className="flex flex-col gap-1">
        <p className="text-sm font-semibold text-primary-ink">
          {modeLabels[attempt.mode]} · <Link to={`/exams/${attempt.examSlug}`}>{attempt.examTitle}</Link>
        </p>
        <h1 className="text-3xl font-extrabold tracking-tight">Your result</h1>
      </header>

      {attempt.finishReason === 'EXPIRED' && (
        <Alert tone="warning" title="Time ran out">
          <p>The answers you gave in time were marked.</p>
        </Alert>
      )}
      {attempt.finishReason === 'ABANDONED' && (
        <Alert tone="info" title="This session was closed after a day without activity">
          <p>The answers you gave were marked.</p>
        </Alert>
      )}

      <Card className="grid gap-6 p-6 sm:grid-cols-[auto_1fr] sm:items-center sm:p-8">
        <div className="flex flex-col items-center gap-1 text-center sm:pr-6">
          <ScoreRing percent={result.percent} />
          {result.passed !== null && (
            <Badge tone={result.passed ? 'free' : 'danger'} className="mt-2">
              {result.passed ? 'Passed' : 'Not passed'} · pass mark {result.passMarkPct}%
            </Badge>
          )}
        </div>
        <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <Stat
            icon={<CircleCheck aria-hidden="true" className="size-5 text-success" />}
            label="Correct"
            value={result.correct}
          />
          <Stat icon={<CircleX aria-hidden="true" className="size-5 text-danger" />} label="Incorrect" value={result.incorrect} />
          <Stat
            icon={<CircleMinus aria-hidden="true" className="size-5 text-subtle" />}
            label="Not answered"
            value={result.unanswered}
          />
          <Stat
            icon={<Clock aria-hidden="true" className="size-5 text-muted" />}
            label="Time"
            value={formatDuration(result.durationSeconds)}
          />
          <div className="col-span-2 text-[14.5px] text-muted sm:col-span-4">
            Score {formatScore(result.score)} out of {formatScore(result.maxScore)}
          </div>
        </dl>
      </Card>

      <section aria-labelledby="topics-heading" className="flex flex-col gap-3">
        <h2 id="topics-heading" className="text-xl font-bold">
          By topic
        </h2>
        {weak.length > 0 && (
          <p className="text-ink-2">
            <strong>Revise next:</strong> {weak.map((t) => t.title).join(', ')}.
          </p>
        )}
        <Card className="flex flex-col divide-y divide-border">
          {result.byTopic.map((t) => (
            <TopicRow key={t.topicId} topic={t} />
          ))}
        </Card>
      </section>

      <div className="flex flex-wrap gap-3">
        <Link to={startPath(attempt.examSlug, attempt.mode)} className={buttonClasses('primary')}>
          <RotateCcw aria-hidden="true" className="size-4" /> Another {modeLabels[attempt.mode].toLowerCase()}
        </Link>
        <Link to={`/exams/${attempt.examSlug}`} className={buttonClasses('secondary')}>
          Back to the exam
        </Link>
        <Link to={`/progress?exam=${attempt.examId}`} className={buttonClasses('ghost')}>
          My progress
        </Link>
      </div>

      <section aria-labelledby="review-heading" className="flex flex-col gap-4">
        <h2 id="review-heading" className="text-xl font-bold">
          Review your answers
        </h2>
        <div role="tablist" aria-label="Show" className="flex flex-wrap gap-2">
          {(Object.keys(filterLabels) as Filter[]).map((f) => (
            <button
              key={f}
              type="button"
              role="tab"
              aria-selected={filter === f}
              onClick={() => setFilter(f)}
              className={cn(
                'min-h-10 rounded-full border px-4 text-[14.5px] font-semibold transition-colors',
                filter === f ? 'border-primary bg-primary text-white' : 'border-border-strong bg-surface hover:border-primary',
              )}
            >
              {filterLabels[f]} ({counts[f]})
            </button>
          ))}
        </div>
        {shown.length === 0 ? (
          <p className="text-muted">Nothing to show here.</p>
        ) : (
          <ol className="flex flex-col gap-4">
            {shown.map((q) => (
              <li key={q.position}>
                <Card className="flex flex-col gap-4 p-5 sm:p-6">
                  <div className="flex flex-wrap items-center gap-2">
                    <OutcomeBadge q={q} />
                    <Badge tone="primary">{q.topicTitle}</Badge>
                    <Badge>{difficultyLabels[q.difficulty]}</Badge>
                  </div>
                  <QuestionView question={toViewQuestion(q)} mode="review" selected={q.selected} number={q.position} />
                </Card>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

const formatScore = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(2));

function Stat({ icon, label, value }: { icon: ReactNode; label: string; value: number | string }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="flex items-center gap-1.5 text-[14px] text-muted">
        {icon}
        {label}
      </dt>
      <dd className="text-2xl font-extrabold tabular-nums">{value}</dd>
    </div>
  );
}

function ScoreRing({ percent }: { percent: number }) {
  const r = 52;
  const c = 2 * Math.PI * r;
  return (
    <div className="relative size-36">
      <svg viewBox="0 0 120 120" className="size-36 -rotate-90" aria-hidden="true">
        <circle cx="60" cy="60" r={r} fill="none" stroke="var(--color-surface-2)" strokeWidth="12" />
        <circle
          cx="60"
          cy="60"
          r={r}
          fill="none"
          stroke="var(--color-primary)"
          strokeWidth="12"
          strokeLinecap="round"
          strokeDasharray={`${(c * percent) / 100} ${c}`}
        />
      </svg>
      <p className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-4xl font-extrabold tabular-nums">{percent}%</span>
        <span className="text-[13px] text-muted">score</span>
      </p>
    </div>
  );
}

function TopicRow({ topic }: { topic: TopicResult }) {
  const weak = topic.percent < WEAK_BELOW;
  return (
    <div className="grid gap-2 px-5 py-3.5 sm:grid-cols-[minmax(160px,1fr)_2fr_auto_auto] sm:items-center sm:gap-4">
      <span className="font-semibold">{topic.title}</span>
      <div
        role="meter"
        aria-label={`${topic.title}: ${topic.correct} of ${topic.total} correct`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={topic.percent}
        className="h-2.5 overflow-hidden rounded-full bg-surface-2"
      >
        <div className={cn('h-full rounded-full', weak ? 'bg-warning' : 'bg-success')} style={{ width: `${topic.percent}%` }} />
      </div>
      <span className="flex items-center gap-2 text-[14.5px] tabular-nums">
        {topic.correct} / {topic.total}
        {weak && <Badge tone="warning">Revise</Badge>}
      </span>
      {topic.level && (
        <span className="text-[13.5px] text-muted">
          Your level: <strong className="text-ink">{topic.levelLabel}</strong>
        </span>
      )}
    </div>
  );
}

function OutcomeBadge({ q }: { q: AttemptQuestion }) {
  const o = outcome(q);
  if (o === 'CORRECT')
    return (
      <Badge tone="free">
        <CircleCheck aria-hidden="true" className="size-3.5" /> Correct
      </Badge>
    );
  if (o === 'INCORRECT')
    return (
      <Badge tone="danger">
        <CircleX aria-hidden="true" className="size-3.5" /> Incorrect
      </Badge>
    );
  return (
    <Badge>
      <CircleMinus aria-hidden="true" className="size-3.5" /> Not answered
    </Badge>
  );
}
