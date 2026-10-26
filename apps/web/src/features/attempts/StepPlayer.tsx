import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, CircleCheck, CircleX, Flag } from 'lucide-react';
import { Alert, Badge, Button, Card, Dialog } from '@/components/ui';
import { modeLabels } from '@/features/exams/api';
import { formatDuration } from '@/features/exams/format';
import { QuestionView } from '@/features/questions/QuestionView';
import { ApiError } from '@/lib/api/problem';
import { attemptKeys, saveAnswer, submitAttempt, type Attempt, type AttemptQuestion } from './api';
import { difficultyLabels, levelLabels } from './modes';
import { Countdown } from './Countdown';
import { toViewQuestion, useQuestionTimer, useSecondsLeft } from './hooks';

const AUTO_NEXT_MS = 2500;

/**
 * Practice, Adaptive, and a Mock exam set up for instant answers: one question at a time. Choosing an answer (or
 * pressing "Check answer" when several can be right) checks it on the server, then shows right or wrong, the correct
 * answer and the explanation. After a correct answer the next question follows on its own; after a wrong one the
 * student moves on when they've read the explanation.
 */
export function StepPlayer({ attempt }: { attempt: Attempt }) {
  const queryClient = useQueryClient();
  // An Adaptive exam gets each question after the previous answer, so fewer than planned may be here yet.
  const total = Math.max(attempt.plannedCount, attempt.questions.length);
  const adaptive = attempt.mode === 'USER_FRIENDLY';
  const firstOpen = attempt.questions.findIndex((q) => !q.review);
  const [index, setIndex] = useState(firstOpen === -1 ? attempt.questions.length - 1 : firstOpen);
  const [confirming, setConfirming] = useState(false);
  const question = attempt.questions[index] ?? attempt.questions[0];
  const secondsLeft = useSecondsLeft(attempt);
  const takeTime = useQuestionTimer(index);

  const replace = (updated: AttemptQuestion) =>
    queryClient.setQueryData<Attempt>(attemptKeys.one(attempt.id), (old) =>
      old ? { ...old, questions: old.questions.map((q) => (q.position === updated.position ? updated : q)) } : old,
    );

  const check = useMutation({
    mutationFn: (selected: string[]) => saveAnswer(attempt.id, question?.position ?? 1, { selected, timeSpentMs: takeTime() }),
    onSuccess: (updated) => {
      replace(updated);
      // The server has chosen the next question from this answer: fetch it.
      if (adaptive) void queryClient.invalidateQueries({ queryKey: attemptKeys.one(attempt.id) });
    },
    onError: (e) => {
      if (e instanceof ApiError && (e.code === 'ATTEMPT_EXPIRED' || e.code === 'ATTEMPT_FINISHED')) {
        void queryClient.invalidateQueries({ queryKey: attemptKeys.one(attempt.id) });
      }
    },
  });
  const finish = useMutation({
    mutationFn: () => submitAttempt(attempt.id),
    onSuccess: (done) => {
      queryClient.setQueryData(attemptKeys.one(attempt.id), done);
      void queryClient.invalidateQueries({ queryKey: attemptKeys.histories() });
    },
  });

  // Time is up on a timed attempt: hand it in (the server marks what was answered in time).
  const handedIn = useRef(false);
  useEffect(() => {
    if (secondsLeft === 0 && !handedIn.current) {
      handedIn.current = true;
      finish.mutate();
    }
  }, [secondsLeft, finish]);

  if (!question) return null;
  const answered = attempt.questions.filter((q) => q.review).length;
  const correct = attempt.questions.filter((q) => q.review?.correct).length;
  const last = index >= total - 1;
  const nextReady = last || attempt.questions[index + 1] !== undefined;
  const goNext = () => {
    if (last) setConfirming(true);
    else if (nextReady) setIndex(index + 1);
  };

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-5 px-4 py-6 sm:px-8 sm:py-8">
      <title>{`${modeLabels[attempt.mode]} · ${attempt.examTitle} · Leenex Labs`}</title>
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-semibold text-primary-ink">{modeLabels[attempt.mode]}</p>
            <h1 className="truncate text-xl font-extrabold tracking-tight sm:text-2xl">{attempt.examTitle}</h1>
          </div>
          <div className="flex items-center gap-2">
            {secondsLeft !== null && <Countdown seconds={secondsLeft} />}
            <Button variant="secondary" size="sm" onClick={() => setConfirming(true)}>
              Finish
            </Button>
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-between gap-2 text-[14.5px]">
          <span className="font-semibold">
            Question {index + 1} of {total}
          </span>
          <span className="text-muted">
            {correct} correct of {answered} answered
            {attempt.sizeBy === 'TIME' && attempt.requestedMinutes ? ` · planned ${attempt.requestedMinutes} min` : ''}
          </span>
        </div>
        <div
          role="progressbar"
          aria-label="Questions answered"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={answered}
          className="h-2 overflow-hidden rounded-full bg-surface-2"
        >
          <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${(answered / total) * 100}%` }} />
        </div>
        {attempt.requestedCount !== null && attempt.requestedCount > total && index === 0 && !answered && (
          <Alert tone="info" title={`Only ${total} questions are available for these topics`}>
            <p>You asked for {attempt.requestedCount}. Choose more topics next time for a longer session.</p>
          </Alert>
        )}
      </header>

      <Card className="flex flex-col gap-5 p-5 sm:p-7">
        <div className="flex flex-wrap gap-2">
          <Badge tone="primary">{question.topicTitle}</Badge>
          <Badge>{difficultyLabels[question.difficulty]}</Badge>
          {adaptive && question.level && (
            <span className="self-center text-[13px] text-muted">
              Chosen for your {levelLabels[question.level]} level on this topic
            </span>
          )}
        </div>
        <QuestionStep
          key={question.position}
          question={question}
          number={index + 1}
          busy={check.isPending}
          onCheck={(selected) => check.mutate(selected)}
        />
        {check.error && !(check.error instanceof ApiError && check.error.code === 'ATTEMPT_EXPIRED') && (
          <Alert tone="error" title="Your answer wasn’t saved">
            <p>{check.error instanceof ApiError ? check.error.message : 'Check your connection and try again.'}</p>
          </Alert>
        )}
        {question.review && (
          <Feedback
            key={`f${question.position}`}
            question={question}
            last={last}
            nextReady={nextReady}
            onNext={goNext}
            autoNext={question.review.correct === true}
          />
        )}
      </Card>

      <nav aria-label="Questions" className="flex flex-wrap items-center justify-between gap-3">
        <Button variant="ghost" disabled={index === 0} onClick={() => setIndex(index - 1)}>
          ← Previous
        </Button>
        {!question.review && !last && !adaptive && (
          <Button variant="ghost" onClick={() => setIndex(index + 1)}>
            Skip <ArrowRight aria-hidden="true" className="size-4" />
          </Button>
        )}
      </nav>

      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title={answered === total ? 'See your results?' : 'Finish this session?'}
        description={
          answered === total
            ? 'Every question is answered.'
            : `${total - answered} question${total - answered === 1 ? ' is' : 's are'} not answered yet and will count as unanswered.`
        }
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirming(false)}>
              Keep going
            </Button>
            <Button loading={finish.isPending} onClick={() => finish.mutate()}>
              See results
            </Button>
          </>
        }
      >
        {finish.isError && (
          <Alert tone="error" title="That didn’t work">
            <p>Check your connection and try again.</p>
          </Alert>
        )}
      </Dialog>
    </div>
  );
}

function QuestionStep(props: {
  question: AttemptQuestion;
  number: number;
  busy: boolean;
  onCheck: (selected: string[]) => void;
}) {
  const { question } = props;
  const [draft, setDraft] = useState<string[]>(question.selected);
  const reviewed = question.review !== null;
  const multi = question.answerType === 'MULTI';
  return (
    <div className="flex flex-col gap-4">
      <QuestionView
        question={toViewQuestion(question)}
        mode={reviewed ? 'review' : 'answering'}
        selected={reviewed ? question.selected : draft}
        number={props.number}
        onSelect={(labels) => {
          if (props.busy) return;
          setDraft(labels);
          // One answer: choosing it is answering. Several answers: the student says when they're done.
          if (!multi) props.onCheck(labels);
        }}
      />
      {multi && !reviewed && (
        <div>
          <Button loading={props.busy} disabled={draft.length === 0} onClick={() => props.onCheck(draft)}>
            Check answer
          </Button>
        </div>
      )}
    </div>
  );
}

function Feedback({
  question,
  last,
  nextReady,
  onNext,
  autoNext,
}: {
  question: AttemptQuestion;
  last: boolean;
  /** False while an Adaptive exam's next question is still being chosen. */
  nextReady: boolean;
  onNext: () => void;
  autoNext: boolean;
}) {
  const review = question.review;
  const [waiting, setWaiting] = useState(autoNext && !last);
  const nextRef = useRef<HTMLButtonElement>(null);
  // The latest onNext, so re-renders (e.g. the countdown ticking) don't restart the wait.
  const next = useRef(onNext);
  useEffect(() => {
    next.current = onNext;
  });
  useEffect(() => {
    nextRef.current?.focus();
  }, []);
  useEffect(() => {
    if (!waiting || !nextReady) return;
    const id = window.setTimeout(() => next.current(), AUTO_NEXT_MS);
    return () => window.clearTimeout(id);
  }, [waiting, nextReady]);
  if (!review) return null;
  return (
    <div className="flex flex-col gap-3 border-t border-border pt-4" aria-live="polite">
      {review.correct ? (
        <p className="flex items-center gap-2 text-lg font-bold text-success-ink">
          <CircleCheck aria-hidden="true" className="size-6" /> Correct!
        </p>
      ) : (
        <p className="flex items-center gap-2 text-lg font-bold text-danger-ink">
          <CircleX aria-hidden="true" className="size-6" /> Not quite. The answer is {review.correctLabels.join(' and ')}.
        </p>
      )}
      <div className="flex flex-wrap items-center gap-3">
        <Button ref={nextRef} onClick={onNext} loading={!nextReady}>
          {last ? 'See results' : 'Next question'} <ArrowRight aria-hidden="true" className="size-4" />
        </Button>
        {waiting && (
          <Button variant="ghost" size="sm" onClick={() => setWaiting(false)}>
            <Flag aria-hidden="true" className="size-4" /> Stay on this question
          </Button>
        )}
        {waiting && <span className="text-[14px] text-muted">Moving on in a moment…</span>}
        {!waiting && question.estSeconds > 0 && (
          <span className="text-[13px] text-muted">Usually takes about {formatDuration(question.estSeconds)}</span>
        )}
      </div>
    </div>
  );
}
