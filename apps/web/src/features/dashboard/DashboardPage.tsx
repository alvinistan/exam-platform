import { useId, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ArrowRight, BookOpenCheck, CircleCheck, Flame, ListChecks, PlayCircle, Target, Timer } from 'lucide-react';
import { Badge, Card, ErrorState, Skeleton, buttonClasses } from '@/components/ui';
import { useMyAccess } from '@/features/access/api';
import { useAttemptHistory, type AttemptSummary } from '@/features/attempts/api';
import { levelLabels, startPath } from '@/features/attempts/modes';
import { modeLabels, useExamBySlug, type Mode } from '@/features/exams/api';
import { useOverview, type FocusTopic, type Overview } from '@/features/progress/api';
import { useAuth } from '@/lib/auth/AuthContext';
import { cn } from '@/lib/cn';

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const timeOfDay = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const dayName = new Intl.DateTimeFormat(undefined, { weekday: 'short' });
const dayLong = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'short' });

function greeting(now = new Date()) {
  const h = now.getHours();
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}

/** "3 min ago", "2 h ago", "yesterday", "12 Oct". */
function ago(iso: string, now = Date.now()) {
  const minutes = Math.round((now - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  if (hours < 48) return 'yesterday';
  return longDate.format(new Date(iso));
}

/**
 * /dashboard — a signed-in student's home: what's unfinished, what to practise next, how they're doing, and their
 * exams. Every number comes from their own attempts (assessment-service) and plan (subscription-service).
 */
export function DashboardPage() {
  const { state } = useAuth();
  const name = state.status === 'signedIn' ? state.user.displayName.split(' ')[0] : '';
  const overview = useOverview();
  const history = useAttemptHistory();
  const me = useMyAccess();

  return (
    <div className="mx-auto flex max-w-[1200px] flex-col gap-8 px-4 py-8 sm:px-8 sm:py-10">
      <title>Dashboard · Leenex Labs</title>
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight">
            {greeting()}
            {name ? `, ${name}` : ''}
          </h1>
          <p className="text-muted">Pick up where you left off, or practise what needs work.</p>
        </div>
        {me.data && (
          <p className="flex items-center gap-2 text-[14.5px]">
            {me.data.premium ? (
              <>
                <Badge tone="premium">Premium</Badge>
                {me.data.entitlement && (
                  <span className="text-muted">until {longDate.format(new Date(me.data.entitlement.endsAt))}</span>
                )}
              </>
            ) : (
              <>
                <Badge tone="free">Free plan</Badge>
                <Link to="/account" className="font-semibold">
                  Your plan
                </Link>
              </>
            )}
          </p>
        )}
      </header>

      {overview.isPending || history.isPending ? (
        <div aria-busy="true" className="flex flex-col gap-4">
          <p role="status" className="sr-only">
            Loading your dashboard…
          </p>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            {[0, 1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-24" />
            ))}
          </div>
          <Skeleton className="h-64 w-full" />
        </div>
      ) : overview.isError || history.isError ? (
        <ErrorState
          title="We couldn’t load your dashboard"
          onRetry={() => {
            void overview.refetch();
            void history.refetch();
          }}
        />
      ) : overview.data.answered === 0 && history.data.length === 0 ? (
        <FirstSteps />
      ) : (
        <Dashboard overview={overview.data} attempts={history.data} />
      )}
    </div>
  );
}

function Dashboard({ overview, attempts }: { overview: Overview; attempts: AttemptSummary[] }) {
  const open = attempts.filter((a) => a.status === 'IN_PROGRESS');
  const recent = attempts.filter((a) => a.status === 'SCORED').slice(0, 5);
  return (
    <>
      {open.length > 0 && (
        <section aria-labelledby="continue-heading" className="flex flex-col gap-3">
          <h2 id="continue-heading" className="text-xl font-bold">
            Continue where you left off
          </h2>
          <ul className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(min(300px,100%),1fr))]">
            {open.map((a) => (
              <li key={a.id}>
                <Card className="flex h-full flex-col gap-3 p-5">
                  <div className="flex items-center gap-2">
                    {a.mode === 'MOCK' ? (
                      <Timer aria-hidden="true" className="size-5 text-primary" />
                    ) : (
                      <BookOpenCheck aria-hidden="true" className="size-5 text-primary" />
                    )}
                    <span className="font-semibold text-primary-ink">{modeLabels[a.mode]}</span>
                  </div>
                  <p className="text-lg font-bold">{a.examTitle}</p>
                  <p className="text-[14px] text-muted">
                    {a.questionCount} questions · started {ago(a.startedAt)}
                    {a.expiresAt && ` · time runs out at ${timeOfDay.format(new Date(a.expiresAt))}`}
                  </p>
                  <div className="mt-auto">
                    <Link to={`/attempts/${a.id}`} className={buttonClasses('primary')}>
                      <PlayCircle aria-hidden="true" className="size-4" /> Continue
                    </Link>
                  </div>
                </Card>
              </li>
            ))}
          </ul>
        </section>
      )}

      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile
          icon={<ListChecks aria-hidden="true" className="size-5 text-primary" />}
          label="Questions answered"
          value={overview.answered}
        />
        <Tile
          icon={<CircleCheck aria-hidden="true" className="size-5 text-success" />}
          label="Correct overall"
          value={`${overview.accuracy}%`}
        />
        <Tile
          icon={<Flame aria-hidden="true" className="size-5 text-warning" />}
          label="Day streak"
          value={overview.streakDays}
          note={
            overview.streakDays === 0
              ? 'Answer a question today to start one'
              : overview.streakDays === 1
                ? 'Keep it going tomorrow'
                : 'days in a row'
          }
        />
        <Tile
          icon={<BookOpenCheck aria-hidden="true" className="size-5 text-primary" />}
          label="Answered this week"
          value={overview.answeredThisWeek}
        />
      </dl>

      <div className="grid gap-6 lg:grid-cols-[1.2fr_1fr]">
        <PractiseNext focus={overview.focus} />
        <Activity days={overview.activity} />
      </div>

      {overview.exams.length > 0 && (
        <section aria-labelledby="exams-heading" className="flex flex-col gap-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 id="exams-heading" className="text-xl font-bold">
              Your exams
            </h2>
            <Link to="/exams" className="font-semibold">
              Browse all exams
            </Link>
          </div>
          <ul className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(min(300px,100%),1fr))]">
            {overview.exams.map((e) => (
              <li key={e.examId}>
                <Card className="flex h-full flex-col gap-3 p-5">
                  <div className="flex items-start justify-between gap-2">
                    <Link to={`/exams/${e.slug}`} className="text-lg font-bold text-ink no-underline hover:underline">
                      {e.title}
                    </Link>
                    {e.topicsNeedingWork > 0 && <Badge tone="warning">{e.topicsNeedingWork} to revise</Badge>}
                  </div>
                  <div
                    role="meter"
                    aria-label={`${e.title}: ${e.accuracy}% correct`}
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={e.accuracy}
                    className="h-2 overflow-hidden rounded-full bg-surface-2"
                  >
                    <div className="h-full rounded-full bg-primary" style={{ width: `${e.accuracy}%` }} />
                  </div>
                  <p className="text-[14px] text-ink-2">
                    <strong>{e.accuracy}%</strong> correct · {e.answered} answered · {e.attempts} session
                    {e.attempts === 1 ? '' : 's'}
                  </p>
                  <p className="text-[13px] text-muted">Last practised {ago(e.lastActivity)}</p>
                  <div className="mt-auto flex flex-wrap gap-3 pt-1">
                    <Link to={`/exams/${e.slug}`} className={buttonClasses('secondary', 'sm')}>
                      Open exam
                    </Link>
                    <Link to={`/progress?exam=${e.examId}`} className={buttonClasses('ghost', 'sm')}>
                      Progress
                    </Link>
                  </div>
                </Card>
              </li>
            ))}
          </ul>
        </section>
      )}

      {recent.length > 0 && (
        <section aria-labelledby="recent-heading" className="flex flex-col gap-3">
          <h2 id="recent-heading" className="text-xl font-bold">
            Recent results
          </h2>
          <Card className="flex flex-col divide-y divide-border">
            {recent.map((a) => (
              <div key={a.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3.5">
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{a.examTitle}</span>
                  <span className="text-[13.5px] text-muted">
                    {modeLabels[a.mode]} · {a.finishedAt ? ago(a.finishedAt) : ''}
                  </span>
                </span>
                <span className="text-[14px] text-ink-2 tabular-nums">
                  {a.correct ?? 0} / {a.questionCount}
                </span>
                <span className="w-14 text-right text-lg font-extrabold tabular-nums">{a.percent ?? 0}%</span>
                <Link to={`/attempts/${a.id}`} className="font-semibold">
                  Review
                </Link>
              </div>
            ))}
          </Card>
        </section>
      )}
    </>
  );
}

function Tile({ icon, label, value, note }: { icon: ReactNode; label: string; value: number | string; note?: string }) {
  return (
    <Card className="flex flex-col gap-1 p-4 sm:p-5">
      <dt className="flex items-center gap-2 text-[14px] text-muted">
        {icon}
        {label}
      </dt>
      <dd className="text-3xl font-extrabold tabular-nums">{value}</dd>
      {note && <dd className="text-[12.5px] text-muted">{note}</dd>}
    </Card>
  );
}

/** The weakest topics across the student's exams, grouped by exam, each group with one button to practise them. */
function PractiseNext({ focus }: { focus: FocusTopic[] }) {
  const exams = [...new Map(focus.map((f) => [f.examId, f])).values()];
  return (
    <section aria-labelledby="next-heading">
      <Card className="flex h-full flex-col gap-4 p-5 sm:p-6">
        <div className="flex items-center gap-2">
          <Target aria-hidden="true" className="size-5 text-warning-ink" />
          <h2 id="next-heading" className="text-xl font-bold">
            Practise next
          </h2>
        </div>
        {focus.length === 0 ? (
          <p className="text-ink-2">
            Nothing needs extra work right now. Keep practising: topics that slip below half right appear here.
          </p>
        ) : (
          exams.map((e) => (
            <FocusGroup
              key={e.examId}
              examSlug={e.examSlug}
              examTitle={e.examTitle}
              topics={focus.filter((f) => f.examId === e.examId)}
              showExam={exams.length > 1}
            />
          ))
        )}
      </Card>
    </section>
  );
}

function FocusGroup(props: { examSlug: string; examTitle: string; topics: FocusTopic[]; showExam: boolean }) {
  // Weak topics go to the Adaptive exam when the exam offers it (it gives them extra questions), else Practice.
  const exam = useExamBySlug(props.examSlug);
  const modes = exam.data?.modes ?? [];
  const mode: Mode | null = modes.includes('USER_FRIENDLY') ? 'USER_FRIENDLY' : modes.includes('PRACTICE') ? 'PRACTICE' : null;
  return (
    <div className="flex flex-col gap-3">
      {props.showExam && <h3 className="text-[15px] font-semibold text-ink-2">{props.examTitle}</h3>}
      <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
        {props.topics.map((t) => (
          <li key={t.topicId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
            <span className="font-semibold">{t.title}</span>
            <span className="flex items-center gap-2 text-[14px] text-muted">
              {t.recentAccuracy}% lately
              <Badge>{levelLabels[t.level] ?? t.levelLabel} level</Badge>
            </span>
          </li>
        ))}
      </ul>
      {mode && (
        <div>
          <Link
            to={`${startPath(props.examSlug, mode)}?topics=${props.topics.map((t) => t.topicId).join(',')}`}
            className={buttonClasses('primary')}
          >
            Practise {props.topics.length === 1 ? 'this topic' : 'these topics'}{' '}
            <ArrowRight aria-hidden="true" className="size-4" />
          </Link>
        </div>
      )}
    </div>
  );
}

/**
 * Questions answered on each of the last 14 days: one series, so one hue (the brand primary) and no legend; the
 * title names it. Hover or focus a bar for the day's numbers; a table carries the same data for screen readers.
 */
function Activity({ days }: { days: Overview['activity'] }) {
  const max = Math.max(1, ...days.map((d) => d.answered));
  const [active, setActive] = useState<number | null>(null);
  const tableId = useId();
  return (
    <section aria-labelledby="activity-heading">
      <Card className="flex h-full flex-col gap-4 p-5 sm:p-6">
        <div>
          <h2 id="activity-heading" className="text-xl font-bold">
            Questions answered, last 14 days
          </h2>
          <p className="text-[13.5px] text-muted">{days.reduce((n, d) => n + d.answered, 0)} in total</p>
        </div>
        <div className="relative">
          <ol className="flex h-40 items-end gap-1 border-b border-border-strong" aria-describedby={tableId}>
            {days.map((d, i) => {
              const label = `${dayLong.format(new Date(`${d.day}T12:00:00`))}: ${d.answered} answered, ${d.correct} correct`;
              return (
                <li key={d.day} className="flex h-full flex-1 items-end">
                  <button
                    type="button"
                    aria-label={label}
                    onMouseEnter={() => setActive(i)}
                    onMouseLeave={() => setActive(null)}
                    onFocus={() => setActive(i)}
                    onBlur={() => setActive(null)}
                    className="group flex h-full w-full items-end justify-center px-px focus-visible:outline-offset-1"
                  >
                    <span
                      className={cn(
                        'block w-full max-w-6 rounded-t-[4px] transition-colors',
                        d.answered ? 'bg-primary group-hover:bg-primary-hover' : 'bg-transparent',
                      )}
                      style={{ height: d.answered ? `${Math.max(4, (d.answered / max) * 100)}%` : 0 }}
                    />
                  </button>
                </li>
              );
            })}
          </ol>
          {active !== null && days[active] && (
            <div
              role="tooltip"
              className="pointer-events-none absolute -top-2 z-10 -translate-x-1/2 -translate-y-full rounded-md border border-border bg-surface px-3 py-2 text-[13px] whitespace-nowrap shadow-raised"
              style={{ left: `${((active + 0.5) / days.length) * 100}%` }}
            >
              <p className="font-semibold">{dayLong.format(new Date(`${days[active].day}T12:00:00`))}</p>
              <p className="text-ink-2">
                {days[active].answered} answered · {days[active].correct} correct
              </p>
            </div>
          )}
          <div className="mt-1.5 flex gap-1 text-[11px] text-muted" aria-hidden="true">
            {days.map((d, i) => (
              <span key={d.day} className="flex-1 text-center">
                {i % 2 === 1 || i === days.length - 1 ? dayName.format(new Date(`${d.day}T12:00:00`)).slice(0, 2) : ''}
              </span>
            ))}
          </div>
        </div>
        <table id={tableId} className="sr-only">
          <caption>Questions answered per day</caption>
          <thead>
            <tr>
              <th scope="col">Day</th>
              <th scope="col">Answered</th>
              <th scope="col">Correct</th>
            </tr>
          </thead>
          <tbody>
            {days.map((d) => (
              <tr key={d.day}>
                <th scope="row">{dayLong.format(new Date(`${d.day}T12:00:00`))}</th>
                <td>{d.answered}</td>
                <td>{d.correct}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </section>
  );
}

function FirstSteps() {
  const steps = [
    ['Choose an exam', 'Browse the catalogue and open an exam you’re preparing for.'],
    ['Pick an exam type', 'Practice, Mock exam or Adaptive exam: choose topics and how long you have.'],
    ['Watch your progress', 'Your level on each topic moves as you answer, and this page shows what to practise next.'],
  ] as const;
  return (
    <Card className="flex flex-col gap-6 p-6 sm:p-8">
      <div>
        <h2 className="text-2xl font-bold">Start your first exam</h2>
        <p className="text-muted">Once you answer a few questions, your progress and suggestions appear here.</p>
      </div>
      <ol className="grid gap-4 sm:grid-cols-3">
        {steps.map(([title, text], i) => (
          <li key={title} className="flex flex-col gap-1.5 rounded-md border border-border p-4">
            <span className="grid size-8 place-items-center rounded-full bg-primary-soft font-bold text-primary-ink">
              {i + 1}
            </span>
            <span className="font-semibold">{title}</span>
            <span className="text-[14.5px] text-muted">{text}</span>
          </li>
        ))}
      </ol>
      <div>
        <Link to="/exams" className={buttonClasses('primary', 'lg')}>
          Browse exams <ArrowRight aria-hidden="true" className="size-5" />
        </Link>
      </div>
    </Card>
  );
}
