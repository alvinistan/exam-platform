import { useId, useState } from 'react';
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ChevronRight, Clock, ListOrdered, Lock } from 'lucide-react';
import { Alert, Badge, Button, Card, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { accessKeys, describeModeAccess, useExamAccess } from '@/features/access/api';
import { modeLabels, useExamBySlug, type ExamDetail, type Mode, type PublicModeSettings } from '@/features/exams/api';
import { formatDuration } from '@/features/exams/format';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import { attemptKeys, startAttempt } from './api';
import { countChoices, minuteChoices, modeFromSlug } from './modes';

const intro: Record<Mode, string> = {
  PRACTICE: 'One question at a time. You’ll see if you’re right, the correct answer and an explanation straight away.',
  MOCK: 'A timed paper, like the real exam. The clock is kept by our server, so it keeps running if you close the page.',
  USER_FRIENDLY:
    'One question at a time with instant answers. Each question is chosen after your last answer: harder when you’re doing well, easier when a topic needs work.',
};

/** /exams/:slug/start/:mode — choose topics and how long, then start. */
export function StartPage() {
  const { slug = '', mode: modeSlug } = useParams();
  const mode = modeFromSlug(modeSlug);
  const exam = useExamBySlug(slug);

  if (!mode) return <Navigate to={`/exams/${slug}`} replace />;
  if (exam.isPending) {
    return (
      <div className="mx-auto flex max-w-3xl flex-col gap-4 px-4 py-10 sm:px-8">
        <p role="status" className="sr-only">
          Loading…
        </p>
        <Skeleton className="h-9 w-2/3" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }
  if (exam.isError) {
    return <ErrorState title="We couldn’t load this exam" onRetry={() => void exam.refetch()} />;
  }
  const settings = exam.data.modeSettings.find((m) => m.mode === mode);
  if (!settings) {
    return (
      <EmptyState
        headingLevel="h1"
        title={`${modeLabels[mode]} isn’t offered for this exam`}
        action={
          <Link to={`/exams/${exam.data.slug}`} className="font-semibold">
            Back to the exam
          </Link>
        }
      >
        <p>Choose another way to study on the exam page.</p>
      </EmptyState>
    );
  }
  return <StartForm exam={exam.data} settings={settings} />;
}

function StartForm({ exam, settings }: { exam: ExamDetail; settings: PublicModeSettings }) {
  const mode = settings.mode;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const access = useExamAccess([exam.id]).data?.[exam.id];
  const modeAccess = access?.modes.find((m) => m.mode === mode);
  const locked = modeAccess?.access === 'LOCKED';

  const topics = exam.subjects.flatMap((s) => s.modules.flatMap((m) => m.topics.map((t) => ({ ...t, subject: s.title }))));
  // "Practise these topics" on My progress links here with ?topics=…; otherwise every topic starts chosen.
  const [params] = useSearchParams();
  const [chosen, setChosen] = useState<string[]>(() => {
    const asked = (params.get('topics') ?? '').split(',').filter((id) => topics.some((t) => t.id === id));
    return asked.length ? asked : topics.map((t) => t.id);
  });
  const counts = countChoices(settings.maxCount);
  const minutes = minuteChoices(mode === 'MOCK' ? settings.timeLimitSeconds : null);
  const [sizeBy, setSizeBy] = useState<'COUNT' | 'TIME'>('COUNT');
  const [count, setCount] = useState(counts.includes(10) ? 10 : (counts[counts.length - 1] ?? 1));
  const [minute, setMinute] = useState(minutes[0] ?? 10);
  // One key per visit to this page: pressing Start twice (or a retried request) starts one attempt.
  const [key] = useState(() => crypto.randomUUID());
  const topicsId = useId();

  const start = useMutation({
    mutationFn: () =>
      startAttempt(
        {
          examId: exam.id,
          mode,
          topicIds: settings.allowTopicChoice && chosen.length < topics.length ? chosen : [],
          sizeBy,
          ...(sizeBy === 'COUNT' ? { questionCount: count } : { minutes: minute }),
        },
        key,
      ),
    onSuccess: (attempt) => {
      queryClient.setQueryData(attemptKeys.one(attempt.id), attempt);
      void queryClient.invalidateQueries({ queryKey: accessKeys.all });
      void queryClient.invalidateQueries({ queryKey: attemptKeys.histories() });
      void navigate(`/attempts/${attempt.id}`);
    },
  });
  const error = start.error instanceof ApiError ? start.error : undefined;
  const noTopics = settings.allowTopicChoice && chosen.length === 0;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-8 sm:px-8 sm:py-10">
      <title>{`${modeLabels[mode]} · ${exam.title} · Leenex Labs`}</title>
      <nav aria-label="Breadcrumb">
        <ol className="flex flex-wrap items-center gap-1 text-sm text-muted">
          <li>
            <Link to={`/exams/${exam.slug}`}>{exam.title}</Link>
          </li>
          <li aria-hidden="true">
            <ChevronRight className="size-4" />
          </li>
          <li aria-current="page">{modeLabels[mode]}</li>
        </ol>
      </nav>
      <header className="flex flex-col gap-2">
        <h1 className="text-3xl font-extrabold tracking-tight">Start a {modeLabels[mode].toLowerCase()}</h1>
        <p className="text-muted">{intro[mode]}</p>
        {modeAccess && (
          <p className="flex flex-wrap items-center gap-2 text-[14.5px]">
            <Badge tone={modeAccess.access === 'LOCKED' ? 'premium' : modeAccess.access === 'OPEN' ? 'premium' : 'free'}>
              {modeAccess.access === 'LOCKED' && <Lock aria-hidden="true" className="size-3.5" />}
              {modeAccess.access === 'OPEN' ? 'Premium' : modeAccess.access === 'FREE' ? 'Free' : 'Premium needed'}
            </Badge>
            <span className="text-ink-2">{describeModeAccess(modeAccess)}</span>
          </p>
        )}
      </header>

      <Card className="flex flex-col gap-7 p-5 sm:p-7">
        <fieldset className="flex flex-col gap-3" aria-describedby={`${topicsId}-help`}>
          <legend className="mb-1 text-lg font-bold">1. Topics</legend>
          {settings.allowTopicChoice ? (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p id={`${topicsId}-help`} className="text-[14.5px] text-muted">
                  {chosen.length === topics.length ? 'All topics' : `${chosen.length} of ${topics.length} topics`} chosen.
                </p>
                <div className="flex gap-1">
                  <Button variant="ghost" size="sm" onClick={() => setChosen(topics.map((t) => t.id))}>
                    Select all
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setChosen([])}>
                    Clear
                  </Button>
                </div>
              </div>
              <ul className="grid gap-2 sm:grid-cols-2">
                {topics.map((t) => {
                  const on = chosen.includes(t.id);
                  return (
                    <li key={t.id}>
                      <label
                        className={cn(
                          'flex min-h-12 cursor-pointer items-center gap-3 rounded-md border px-3.5 py-2.5 transition-colors',
                          on ? 'border-primary bg-primary-soft' : 'border-border-strong bg-surface hover:border-primary',
                        )}
                      >
                        <input
                          type="checkbox"
                          className="size-5 flex-none accent-[var(--color-primary)]"
                          checked={on}
                          onChange={() => setChosen(on ? chosen.filter((id) => id !== t.id) : [...chosen, t.id])}
                        />
                        <span className="font-semibold">{t.title}</span>
                      </label>
                    </li>
                  );
                })}
              </ul>
              {noTopics && <p className="text-[14px] font-semibold text-warning-ink">Choose at least one topic.</p>}
            </>
          ) : (
            <p id={`${topicsId}-help`} className="text-ink-2">
              Every topic of the exam is included ({topics.length}).
            </p>
          )}
        </fieldset>

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-lg font-bold">2. How long?</legend>
          <div role="radiogroup" aria-label="Choose by" className="grid grid-cols-2 gap-2 sm:max-w-md">
            {(
              [
                ['COUNT', 'Number of questions', ListOrdered],
                ['TIME', 'Time I have', Clock],
              ] as const
            ).map(([value, label, Icon]) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={sizeBy === value}
                onClick={() => setSizeBy(value)}
                className={cn(
                  'flex min-h-12 items-center justify-center gap-2 rounded-md border px-3 font-semibold transition-colors',
                  sizeBy === value
                    ? 'border-primary bg-primary text-white'
                    : 'border-border-strong bg-surface hover:border-primary',
                )}
              >
                <Icon aria-hidden="true" className="size-4" /> {label}
              </button>
            ))}
          </div>
          {sizeBy === 'COUNT' ? (
            <Choices
              label="Questions"
              values={counts}
              value={count}
              onChange={setCount}
              format={(n) => `${n}`}
              help={`Picked at random from the topics you chose, up to ${settings.maxCount}. Questions you saw this week come last.`}
            />
          ) : (
            <Choices
              label="Minutes"
              values={minutes}
              value={minute}
              onChange={setMinute}
              format={(n) => `${n} min`}
              help={
                mode === 'MOCK'
                  ? 'The paper gets as many questions as fit in this time, and the countdown is this long.'
                  : 'We fit in as many questions as you can answer in this time, using each question’s estimated solving time.'
              }
            />
          )}
          {mode === 'MOCK' && sizeBy === 'COUNT' && settings.timeLimitSeconds && (
            <p className="text-[14px] text-muted">
              The countdown is the time these questions usually take, never more than {formatDuration(settings.timeLimitSeconds)}.
            </p>
          )}
        </fieldset>

        {mode === 'MOCK' && (
          <Alert
            tone="info"
            title={settings.feedbackTiming === 'AFTER_SUBMIT' ? 'Answers come after you submit' : 'Answers after each question'}
          >
            <p>
              {settings.feedbackTiming === 'AFTER_SUBMIT'
                ? 'You can move between questions, flag them and change answers until you submit or the time runs out.'
                : 'Each answer is checked as you give it, and the clock keeps running.'}
            </p>
          </Alert>
        )}

        {error && <StartError error={error} examSlug={exam.slug} />}
        {locked && (
          <Alert tone="premium" title={`You’ve used your free ${modeLabels[mode].toLowerCase()} attempts`}>
            <p>Your past results are kept. Premium gives you unlimited attempts; an administrator can add it to your account.</p>
          </Alert>
        )}

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-5">
          <Link to={`/exams/${exam.slug}`} className="inline-flex min-h-11 items-center font-semibold">
            Cancel
          </Link>
          <Button size="lg" loading={start.isPending} disabled={locked || noTopics} onClick={() => start.mutate()}>
            Start {modeLabels[mode].toLowerCase()}
          </Button>
        </div>
      </Card>
    </div>
  );
}

function Choices(props: {
  label: string;
  values: number[];
  value: number;
  onChange: (n: number) => void;
  format: (n: number) => string;
  help: string;
}) {
  const helpId = useId();
  return (
    <div className="flex flex-col gap-2">
      <div role="radiogroup" aria-label={props.label} aria-describedby={helpId} className="flex flex-wrap gap-2">
        {props.values.map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={props.value === n}
            onClick={() => props.onChange(n)}
            className={cn(
              'min-h-11 min-w-16 rounded-md border px-4 font-semibold tabular-nums transition-colors',
              props.value === n
                ? 'border-primary bg-primary-soft text-primary-ink'
                : 'border-border-strong bg-surface hover:border-primary',
            )}
          >
            {props.format(n)}
          </button>
        ))}
      </div>
      <p id={helpId} className="text-[14px] text-muted">
        {props.help}
      </p>
    </div>
  );
}

function StartError({ error, examSlug }: { error: ApiError; examSlug: string }) {
  if (error.code === 'CONFLICT' && error.problem?.attemptId) {
    return (
      <Alert tone="warning" title="You have a mock exam in progress">
        <p>
          Finish it before starting another. <Link to={`/attempts/${error.problem.attemptId}`}>Continue your mock exam</Link>
        </p>
      </Alert>
    );
  }
  if (error.code === 'FREE_ALLOWANCE_USED' || error.code === 'SUBSCRIPTION_REQUIRED') {
    return (
      <Alert tone="premium" title="Premium needed">
        <p>{error.message}</p>
      </Alert>
    );
  }
  if (error.code === 'EXAM_UNAVAILABLE') {
    return (
      <Alert tone="error" title="This exam isn’t available">
        <p>
          <Link to={`/exams/${examSlug}`}>Back to the exam</Link>
        </p>
      </Alert>
    );
  }
  return (
    <Alert tone="error" title="We couldn’t start that">
      <p>{error.problem?.errors?.[0]?.message ?? error.message}</p>
    </Alert>
  );
}
