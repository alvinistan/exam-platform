import { Link, useParams } from 'react-router';
import { SearchX } from 'lucide-react';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { useAttempt } from './api';
import { MockPlayer } from './MockPlayer';
import { ResultView } from './ResultView';
import { StepPlayer } from './StepPlayer';

/**
 * /attempts/:attemptId — the attempt itself. Finished attempts show the result and review; running ones the player
 * that matches how answers are shown. Reloading the page carries on where the student was, with the same questions
 * and the same end time.
 */
export function AttemptPage() {
  const { attemptId = '' } = useParams();
  const attempt = useAttempt(attemptId);

  if (attempt.isPending) {
    return (
      <div className="mx-auto flex max-w-3xl flex-col gap-4 px-4 py-10 sm:px-8">
        <p role="status" className="sr-only">
          Loading your questions…
        </p>
        <Skeleton className="h-8 w-1/2" />
        <Skeleton className="h-80 w-full" />
      </div>
    );
  }
  if (attempt.isError) {
    const error = attempt.error instanceof ApiError ? attempt.error : undefined;
    if (error?.isNotFound) {
      return (
        <EmptyState
          headingLevel="h1"
          icon={SearchX}
          title="We couldn’t find that attempt"
          action={
            <Link to="/exams" className="font-semibold">
              Browse exams
            </Link>
          }
        >
          <p>It may belong to another account.</p>
        </EmptyState>
      );
    }
    return (
      <ErrorState
        title="We couldn’t load your questions"
        onRetry={() => void attempt.refetch()}
        correlationId={error?.correlationId}
      >
        <p>Your answers so far are saved. Please try again in a moment.</p>
      </ErrorState>
    );
  }
  const a = attempt.data;
  if (a.status === 'SCORED') return <ResultView attempt={a} />;
  return a.feedback === 'IMMEDIATE' ? <StepPlayer attempt={a} /> : <MockPlayer attempt={a} />;
}
