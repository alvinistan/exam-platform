import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Flag, LayoutGrid } from 'lucide-react';
import { Alert, Badge, Button, Card, Dialog } from '@/components/ui';
import { modeLabels } from '@/features/exams/api';
import { QuestionView } from '@/features/questions/QuestionView';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import { attemptKeys, saveAnswer, submitAttempt, type Attempt, type AttemptQuestion } from './api';
import { Countdown } from './Countdown';
import { toViewQuestion, useQuestionTimer, useSecondsLeft } from './hooks';

/**
 * A Mock exam with answers after submitting: a countdown from the server's end time, a question map (answered,
 * flagged, current), answers saved as they are chosen and changeable until the end, a review before submitting, and
 * an automatic hand-in when time runs out. Nothing about right or wrong reaches the browser before then.
 */
export function MockPlayer({ attempt }: { attempt: Attempt }) {
  const queryClient = useQueryClient();
  const total = attempt.questions.length;
  const [index, setIndex] = useState(0);
  const [reviewing, setReviewing] = useState(false);
  const [mapOpen, setMapOpen] = useState(false);
  const [timedOut, setTimedOut] = useState(false);
  const question = attempt.questions[index] ?? attempt.questions[0];
  const secondsLeft = useSecondsLeft(attempt);
  const takeTime = useQuestionTimer(index);

  const replace = (updated: AttemptQuestion) =>
    queryClient.setQueryData<Attempt>(attemptKeys.one(attempt.id), (old) =>
      old ? { ...old, questions: old.questions.map((q) => (q.position === updated.position ? updated : q)) } : old,
    );

  const save = useMutation({
    mutationFn: (v: { position: number; selected?: string[]; flagged?: boolean }) =>
      saveAnswer(attempt.id, v.position, {
        ...(v.selected ? { selected: v.selected } : {}),
        ...(v.flagged !== undefined ? { flagged: v.flagged } : {}),
        timeSpentMs: takeTime(),
      }),
    onMutate: (v) => {
      // Show the choice at once; the server's answer replaces it.
      const current = attempt.questions.find((q) => q.position === v.position);
      if (current) {
        replace({
          ...current,
          ...(v.selected ? { selected: v.selected, answered: v.selected.length > 0 } : {}),
          ...(v.flagged !== undefined ? { flagged: v.flagged } : {}),
        });
      }
    },
    onSuccess: replace,
    onError: (e) => {
      if (e instanceof ApiError && (e.code === 'ATTEMPT_EXPIRED' || e.code === 'ATTEMPT_FINISHED')) setTimedOut(true);
      void queryClient.invalidateQueries({ queryKey: attemptKeys.one(attempt.id) });
    },
  });
  const finish = useMutation({
    mutationFn: () => submitAttempt(attempt.id),
    onSuccess: (done) => {
      queryClient.setQueryData(attemptKeys.one(attempt.id), done);
      void queryClient.invalidateQueries({ queryKey: attemptKeys.histories() });
    },
  });

  const handedIn = useRef(false);
  useEffect(() => {
    if (secondsLeft === 0 && !handedIn.current) {
      handedIn.current = true;
      setTimedOut(true);
      finish.mutate();
    }
  }, [secondsLeft, finish]);

  if (!question) return null;
  const answered = attempt.questions.filter((q) => q.answered).length;
  const flagged = attempt.questions.filter((q) => q.flagged);
  const unanswered = attempt.questions.filter((q) => !q.answered);

  const map = (
    <QuestionMap
      questions={attempt.questions}
      current={index}
      onPick={(i) => {
        setIndex(i);
        setMapOpen(false);
        setReviewing(false);
      }}
    />
  );

  return (
    <div className="mx-auto flex max-w-[1100px] flex-col gap-5 px-4 py-6 sm:px-8 sm:py-8">
      <title>{`${modeLabels.MOCK} · ${attempt.examTitle} · Leenex Labs`}</title>
      <header className="sticky top-0 z-20 -mx-4 flex flex-wrap items-center justify-between gap-3 border-b border-border bg-bg/95 px-4 py-3 backdrop-blur sm:-mx-8 sm:px-8">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-primary-ink">{modeLabels.MOCK}</p>
          <h1 className="truncate text-lg font-extrabold tracking-tight sm:text-xl">{attempt.examTitle}</h1>
        </div>
        <div className="flex items-center gap-2">
          {secondsLeft !== null && <Countdown seconds={secondsLeft} />}
          <Button onClick={() => setReviewing(true)}>Submit…</Button>
        </div>
      </header>

      {timedOut && (
        <Alert tone="warning" title="Time is up">
          <p>Your answers given in time are being marked.</p>
        </Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
        <div className="flex min-w-0 flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-2 text-[14.5px]">
            <span className="font-semibold">
              Question {index + 1} of {total}
            </span>
            <span className="text-muted">
              {answered} answered · {flagged.length} flagged
            </span>
            <Button
              variant="secondary"
              size="sm"
              className="lg:hidden"
              aria-expanded={mapOpen}
              onClick={() => setMapOpen(!mapOpen)}
            >
              <LayoutGrid aria-hidden="true" className="size-4" /> All questions
            </Button>
          </div>
          {mapOpen && <Card className="p-4 lg:hidden">{map}</Card>}

          <Card className="flex flex-col gap-5 p-5 sm:p-7">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Badge tone="primary">{question.topicTitle}</Badge>
              <Button
                variant={question.flagged ? 'secondary' : 'ghost'}
                size="sm"
                aria-pressed={question.flagged}
                onClick={() => save.mutate({ position: question.position, flagged: !question.flagged })}
              >
                <Flag aria-hidden="true" className={cn('size-4', question.flagged && 'fill-warning text-warning')} />
                {question.flagged ? 'Flagged for review' : 'Flag for review'}
              </Button>
            </div>
            <QuestionView
              key={question.position}
              question={toViewQuestion(question)}
              mode="answering"
              selected={question.selected}
              number={index + 1}
              onSelect={(labels) => save.mutate({ position: question.position, selected: labels })}
            />
            {question.answered && (
              <div>
                <Button variant="ghost" size="sm" onClick={() => save.mutate({ position: question.position, selected: [] })}>
                  Clear my answer
                </Button>
              </div>
            )}
            {save.isError && !timedOut && (
              <Alert tone="error" title="Your last answer wasn’t saved">
                <p>Check your connection and choose it again.</p>
              </Alert>
            )}
          </Card>

          <nav aria-label="Move between questions" className="flex items-center justify-between gap-3">
            <Button variant="secondary" disabled={index === 0} onClick={() => setIndex(index - 1)}>
              ← Previous
            </Button>
            {index < total - 1 ? (
              <Button variant="secondary" onClick={() => setIndex(index + 1)}>
                Next →
              </Button>
            ) : (
              <Button onClick={() => setReviewing(true)}>Review and submit</Button>
            )}
          </nav>
        </div>

        <aside aria-label="Question map" className="hidden lg:block">
          <Card className="sticky top-24 flex flex-col gap-3 p-4">
            <h2 className="font-bold">Questions</h2>
            {map}
          </Card>
        </aside>
      </div>

      <Dialog
        open={reviewing}
        onClose={() => setReviewing(false)}
        title="Submit your mock exam?"
        description={`${answered} of ${total} answered. You can’t change answers after submitting.`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setReviewing(false)}>
              Back to the paper
            </Button>
            <Button loading={finish.isPending} onClick={() => finish.mutate()}>
              Submit
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3 text-[14.5px]">
          {unanswered.length > 0 && (
            <Jump
              label={`Not answered (${unanswered.length})`}
              questions={unanswered}
              all={attempt.questions}
              onPick={(i) => {
                setIndex(i);
                setReviewing(false);
              }}
            />
          )}
          {flagged.length > 0 && (
            <Jump
              label={`Flagged (${flagged.length})`}
              questions={flagged}
              all={attempt.questions}
              onPick={(i) => {
                setIndex(i);
                setReviewing(false);
              }}
            />
          )}
          {finish.isError && (
            <Alert tone="error" title="That didn’t work">
              <p>Check your connection and try again.</p>
            </Alert>
          )}
        </div>
      </Dialog>
    </div>
  );
}

function QuestionMap({
  questions,
  current,
  onPick,
}: {
  questions: AttemptQuestion[];
  current: number;
  onPick: (i: number) => void;
}) {
  return (
    <div className="flex flex-col gap-3">
      <ol className="grid grid-cols-[repeat(auto-fill,minmax(44px,1fr))] gap-2">
        {questions.map((q, i) => (
          <li key={q.position}>
            <button
              type="button"
              onClick={() => onPick(i)}
              aria-current={i === current ? 'step' : undefined}
              aria-label={`Question ${i + 1}, ${q.answered ? 'answered' : 'not answered'}${q.flagged ? ', flagged' : ''}`}
              className={cn(
                'relative grid size-11 place-items-center rounded-md border text-[14.5px] font-bold tabular-nums transition-colors',
                q.answered ? 'border-primary bg-primary-soft text-primary-ink' : 'border-border-strong bg-surface text-ink-2',
                i === current && 'ring-2 ring-primary ring-offset-2',
              )}
            >
              {i + 1}
              {q.flagged && (
                <Flag aria-hidden="true" className="absolute -top-1.5 -right-1.5 size-3.5 fill-warning text-warning" />
              )}
            </button>
          </li>
        ))}
      </ol>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-[13px] text-muted">
        <li className="flex items-center gap-1.5">
          <span aria-hidden="true" className="size-3 rounded-sm border border-primary bg-primary-soft" /> Answered
        </li>
        <li className="flex items-center gap-1.5">
          <span aria-hidden="true" className="size-3 rounded-sm border border-border-strong bg-surface" /> Not answered
        </li>
        <li className="flex items-center gap-1.5">
          <Flag aria-hidden="true" className="size-3 fill-warning text-warning" /> Flagged
        </li>
      </ul>
    </div>
  );
}

function Jump(props: { label: string; questions: AttemptQuestion[]; all: AttemptQuestion[]; onPick: (i: number) => void }) {
  return (
    <div className="flex flex-col gap-1.5">
      <p className="font-semibold">{props.label}</p>
      <div className="flex flex-wrap gap-1.5">
        {props.questions.map((q) => {
          const i = props.all.indexOf(q);
          return (
            <Button
              key={q.position}
              variant="secondary"
              size="sm"
              onClick={() => props.onPick(i)}
              aria-label={`Go to question ${i + 1}`}
            >
              {i + 1}
            </Button>
          );
        })}
      </div>
    </div>
  );
}
