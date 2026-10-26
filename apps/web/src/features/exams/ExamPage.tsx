import { Link, Navigate, useParams } from 'react-router';
import { BookOpenCheck, ChevronRight, CircleOff, Clock, KeyRound, ListChecks, Lock, SearchX, Sparkles, Timer, type LucideIcon } from 'lucide-react';
import { Badge, Button, Card, EmptyState, ErrorState, Skeleton, buttonClasses } from '@/components/ui';
import { AccessBadge } from '@/features/access/AccessBadge';
import { describeModeAccess, useExamAccess, type ModeAccess } from '@/features/access/api';
import { useAttemptHistory } from '@/features/attempts/api';
import { startPath } from '@/features/attempts/modes';
import { useAuth } from '@/lib/auth/AuthContext';
import { ApiError } from '@/lib/api/problem';
import { modeLabels, useExamBySlug, type ExamDetail, type Mode, type PublicModeSettings } from './api';
import { modeFacts } from './format';

const modeIcons: Record<Mode, LucideIcon> = {
  PRACTICE: BookOpenCheck,
  MOCK: Timer,
  USER_FRIENDLY: Sparkles,
};

const modeIntro: Record<Mode, string> = {
  PRACTICE: 'Learn as you go, one question at a time, with instant answers.',
  MOCK: 'Sit a full paper under exam conditions.',
  USER_FRIENDLY: 'One question at a time with instant answers, on the topics you choose.',
};

/**
 * One template renders every exam: the slug in the URL is resolved by catalog-service, so a newly published exam
 * appears here without any frontend change (FR-CAT-09). A renamed exam's old address moves to the new one.
 */
export function ExamPage() {
  const { slug = '' } = useParams();
  const { data: exam, error, isPending, refetch } = useExamBySlug(slug);

  if (isPending) {
    return (
      <div className="mx-auto flex max-w-[1200px] flex-col gap-4 px-4 py-12 sm:px-8">
        <p role="status" className="sr-only">
          Loading exam…
        </p>
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-10 w-2/3" />
        <Skeleton className="h-5 w-1/2" />
        <Skeleton className="mt-6 h-40 w-full" />
      </div>
    );
  }

  if (error) {
    const apiError = error instanceof ApiError ? error : undefined;
    if (apiError?.isNotFound) {
      const unavailable = apiError.code === 'EXAM_UNAVAILABLE';
      return (
        <>
          <title>{`${unavailable ? 'Exam unavailable' : 'Exam not found'} · Leenex Labs`}</title>
          <EmptyState
            headingLevel="h1"
            icon={unavailable ? CircleOff : SearchX}
            title={unavailable ? 'This exam isn’t available' : 'Exam not found'}
            action={
              <Link to="/exams" className={buttonClasses('secondary')}>
                Browse exams
              </Link>
            }
          >
            <p>
              {unavailable
                ? 'It has been retired or isn’t open yet. Your past results are kept.'
                : 'Check the address, or find the exam in the catalogue.'}
            </p>
          </EmptyState>
        </>
      );
    }
    return (
      <ErrorState title="We couldn't load this exam" onRetry={() => void refetch()} correlationId={apiError?.correlationId}>
        <p>{apiError?.isNetworkError ? apiError.message : 'Please try again in a moment.'}</p>
      </ErrorState>
    );
  }

  if (exam.slug !== slug) {
    return <Navigate to={`/exams/${exam.slug}`} replace />;
  }

  return <ExamDetailView exam={exam} />;
}

function ExamDetailView({ exam }: { exam: ExamDetail }) {
  const { state } = useAuth();
  const signedIn = state.status === 'signedIn';
  const access = useExamAccess([exam.id]).data?.[exam.id];
  const allLocked = !!access && access.modes.length > 0 && access.modes.every((m) => m.access === 'LOCKED');
  const topics = exam.subjects.flatMap((s) => s.modules.flatMap((m) => m.topics));
  return (
    <article className="mx-auto flex max-w-[1200px] flex-col gap-8 px-4 py-10 sm:px-8">
      <title>{`${exam.seo.title ?? exam.title} · Leenex Labs`}</title>
      {exam.seo.description && <meta name="description" content={exam.seo.description} />}

      <header className="flex flex-col gap-3">
        <nav aria-label="Breadcrumb">
          <ol className="flex flex-wrap items-center gap-1 text-sm text-muted">
            <li>
              <Link to="/exams">Exams</Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-4" />
            </li>
            <li>
              <Link to={`/exams?category=${encodeURIComponent(exam.category.slug)}`}>{exam.category.name}</Link>
            </li>
          </ol>
        </nav>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="max-w-3xl text-3xl font-extrabold tracking-tight text-balance sm:text-4xl">{exam.title}</h1>
          <AccessBadge status={access} />
        </div>
        {exam.shortDescription && <p className="max-w-2xl text-lg text-muted">{exam.shortDescription}</p>}
      </header>

      <div className="grid gap-8 lg:grid-cols-[1fr_320px]">
        <div className="flex min-w-0 flex-col gap-8">
          {allLocked && signedIn && <LockedPreview />}
          <section aria-labelledby="modes-heading" className="flex flex-col gap-4">
            <h2 id="modes-heading" className="text-xl font-bold">
              Choose an exam type
            </h2>
            {exam.modeSettings.length === 0 ? (
              <p className="text-muted">No exam types are open for this exam yet.</p>
            ) : (
              <ul className="grid gap-4 [grid-template-columns:repeat(auto-fit,minmax(min(240px,100%),1fr))]">
                {exam.modeSettings.map((settings) => (
                  <ModeCard key={settings.mode} exam={exam} settings={settings} access={access?.modes.find((m) => m.mode === settings.mode)} signedIn={signedIn} />
                ))}
              </ul>
            )}
          </section>

          {signedIn && <RecentAttempts examId={exam.id} />}

          {exam.instructions && (
            <section aria-labelledby="instructions-heading" className="flex flex-col gap-3">
              <h2 id="instructions-heading" className="text-xl font-bold">
                Before you start
              </h2>
              <p className="max-w-3xl whitespace-pre-line text-ink-2">{exam.instructions}</p>
            </section>
          )}

          <section aria-labelledby="syllabus-heading" className="flex flex-col gap-3">
            <h2 id="syllabus-heading" className="text-xl font-bold">
              Topics
            </h2>
            {topics.length === 0 ? (
              <p className="text-muted">The topic list will appear here.</p>
            ) : (
              exam.subjects.map((subject) => (
                <div key={subject.id} className="flex flex-col gap-2">
                  {exam.subjects.length > 1 && <h3 className="text-[15px] font-semibold text-ink-2">{subject.title}</h3>}
                  <ul className="flex flex-wrap gap-2">
                    {subject.modules.flatMap((m) => m.topics).map((topic) => (
                      <li key={topic.id} className="rounded-full bg-surface px-3.5 py-1.5 text-[14px] font-semibold text-ink-2 ring-1 ring-border">
                        {topic.title}
                      </li>
                    ))}
                  </ul>
                </div>
              ))
            )}
          </section>
        </div>

        <aside aria-label="Exam summary" className="lg:sticky lg:top-6 lg:self-start">
          <Card className="flex flex-col gap-4 p-5">
            <h2 className="text-lg font-bold">At a glance</h2>
            <dl className="flex flex-col gap-3 text-[14.5px]">
              <div className="flex items-center gap-3">
                <dt className="flex items-center gap-2 text-muted">
                  <ListChecks aria-hidden="true" className="size-4" /> Topics
                </dt>
                <dd className="ml-auto font-semibold">{topics.length}</dd>
              </div>
              <div className="flex items-center gap-3">
                <dt className="flex items-center gap-2 text-muted">
                  <Clock aria-hidden="true" className="size-4" /> Exam types
                </dt>
                <dd className="ml-auto text-right font-semibold">{exam.modes.map((m) => modeLabels[m]).join(', ') || '—'}</dd>
              </div>
              {access?.premium && (
                <div className="flex items-center gap-3">
                  <dt className="flex items-center gap-2 text-muted">
                    <KeyRound aria-hidden="true" className="size-4" /> Access
                  </dt>
                  <dd className="ml-auto font-semibold">Premium (yours)</dd>
                </div>
              )}
            </dl>
            <p className="rounded-md bg-surface-2 px-3 py-2.5 text-[14px] text-ink-2">
              Each exam type has a few free attempts. Premium gives you unlimited attempts, and your results are always kept.
            </p>
          </Card>
        </aside>
      </div>
    </article>
  );
}

function ModeCard({
  exam,
  settings,
  access,
  signedIn,
}: {
  exam: ExamDetail;
  settings: PublicModeSettings;
  access: ModeAccess | undefined;
  signedIn: boolean;
}) {
  const { signIn } = useAuth();
  const Icon = modeIcons[settings.mode];
  const locked = access?.access === 'LOCKED';
  const headingId = `mode-${settings.mode}`;
  return (
    <li>
      <Card className="flex h-full flex-col gap-3 p-5" aria-labelledby={headingId}>
        <div className="flex items-center gap-3">
          <span className="flex size-9 items-center justify-center rounded-md bg-primary-soft text-primary">
            <Icon aria-hidden="true" className="size-5" />
          </span>
          <h3 id={headingId} className="text-lg font-bold">
            {modeLabels[settings.mode]}
          </h3>
          {locked && (
            <Badge tone="premium" className="ml-auto">
              <Lock aria-hidden="true" className="size-3.5" /> Premium
            </Badge>
          )}
        </div>
        <p className="text-[14.5px] text-muted">{modeIntro[settings.mode]}</p>
        <ul className="flex flex-1 flex-col gap-1.5 text-[14.5px] text-ink-2">
          {modeFacts(settings).map((fact) => (
            <li key={fact} className="flex gap-2">
              <span aria-hidden="true" className="mt-2 size-1.5 flex-none rounded-full bg-primary" />
              {fact}
            </li>
          ))}
        </ul>
        {access && (
          <p className={locked ? 'text-[14px] font-semibold text-premium-ink' : 'text-[14px] font-semibold text-success-ink'}>
            {describeModeAccess(access)}
          </p>
        )}
        {!signedIn ? (
          <Button variant="secondary" onClick={() => signIn(startPath(exam.slug, settings.mode))}>
            Sign in to start
          </Button>
        ) : locked ? (
          <Button variant="secondary" disabled>
            <Lock aria-hidden="true" className="size-4" /> Needs Premium
          </Button>
        ) : (
          <Link to={startPath(exam.slug, settings.mode)} className={buttonClasses('primary')} aria-describedby={headingId}>
            Start
          </Link>
        )}
      </Card>
    </li>
  );
}

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

function RecentAttempts({ examId }: { examId: string }) {
  const history = useAttemptHistory(examId);
  const items = history.data?.slice(0, 8) ?? [];
  if (!history.data || items.length === 0) return null;
  return (
    <section aria-labelledby="recent-heading" className="flex flex-col gap-3">
      <h2 id="recent-heading" className="text-xl font-bold">
        Your attempts
      </h2>
      <Card className="flex flex-col divide-y divide-border">
        {items.map((a) => (
          <div key={a.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3.5">
            <span className="min-w-28 font-semibold">{modeLabels[a.mode]}</span>
            <span className="text-[14px] text-muted">{dateTime.format(new Date(a.startedAt))}</span>
            <span className="text-[14px] text-ink-2">{a.questionCount} questions</span>
            <span className="ml-auto flex items-center gap-3">
              {a.status === 'SCORED' ? (
                <>
                  <span className="font-bold tabular-nums">{a.percent ?? 0}%</span>
                  <Link to={`/attempts/${a.id}`} className="font-semibold">
                    See result
                  </Link>
                </>
              ) : (
                <Link to={`/attempts/${a.id}`} className={buttonClasses('secondary', 'sm')}>
                  Continue
                </Link>
              )}
            </span>
          </div>
        ))}
      </Card>
    </section>
  );
}

/**
 * What a signed-in student sees when every exam type's free attempts are used up. The blurred area is drawn shapes
 * only: the API never sends question content without access, so there is nothing real to hide.
 */
function LockedPreview() {
  return (
    <section aria-labelledby="locked-heading" className="grid overflow-hidden rounded-lg border border-[#d9ccfb] bg-surface">
      {/* Both layers share one grid cell, so the box grows to whichever is taller (no clipping on phones). */}
      <div aria-hidden="true" className="pointer-events-none flex flex-col gap-4 p-6 blur-[6px] select-none [grid-area:1/1]">
        <div className="h-4 w-3/4 rounded bg-surface-2" />
        <div className="h-4 w-1/2 rounded bg-surface-2" />
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="flex items-center gap-3 rounded-md border border-border p-3">
            <span className="size-5 rounded-full border-2 border-border-strong" />
            <span className="h-3 rounded bg-surface-2" style={{ width: `${40 + i * 12}%` }} />
          </div>
        ))}
      </div>
      <div className="relative z-10 flex items-center justify-center bg-surface/70 p-4 [grid-area:1/1]">
        <div className="flex max-w-md flex-col items-center gap-3 rounded-lg border border-[#d9ccfb] bg-surface p-6 text-center shadow-raised">
          <span className="flex size-11 items-center justify-center rounded-full bg-premium-soft text-premium-ink">
            <Lock aria-hidden="true" className="size-5" />
          </span>
          <Badge tone="premium">Premium</Badge>
          <h2 id="locked-heading" className="text-lg font-bold">
            You’ve used your free attempts
          </h2>
          <p className="text-[14.5px] text-ink-2">
            Your results are kept below. Premium gives you unlimited attempts; plans are on the way, and until then an
            administrator can add Premium to your account.
          </p>
        </div>
      </div>
    </section>
  );
}
