import { Link, useParams } from 'react-router';
import { Badge, buttonClasses, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { useExamBySlug, type Mode } from './api';

const modeLabels: Record<Mode, string> = {
  PRACTICE: 'Practice',
  MOCK: 'Mock exam',
  USER_FRIENDLY: 'Guided practice',
};

/**
 * One template renders every exam: the slug in the URL is resolved by catalog-service, so a newly published exam
 * appears here without any frontend change. Phase 6 adds the full detail (subjects, modes, access status).
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
        <Skeleton className="h-10 w-2/3" />
        <Skeleton className="h-5 w-1/2" />
      </div>
    );
  }

  if (error) {
    if (error instanceof ApiError && error.isNotFound) {
      return (
        <EmptyState
          headingLevel="h1"
          title="Exam not found"
          action={
            <Link to="/" className={buttonClasses('secondary')}>
              Back to home
            </Link>
          }
        >
          <p>This exam doesn't exist or isn't available right now.</p>
        </EmptyState>
      );
    }
    const apiError = error instanceof ApiError ? error : undefined;
    return (
      <ErrorState title="We couldn't load this exam" onRetry={() => void refetch()} correlationId={apiError?.correlationId}>
        <p>{apiError?.isNetworkError ? apiError.message : 'Please try again in a moment.'}</p>
      </ErrorState>
    );
  }

  return (
    <article className="mx-auto flex max-w-[1200px] flex-col gap-4 px-4 py-12 sm:px-8">
      <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">{exam.title}</h1>
      {exam.shortDescription && <p className="max-w-2xl text-lg text-muted">{exam.shortDescription}</p>}
      {exam.modes.length > 0 && (
        <ul className="flex flex-wrap gap-2" aria-label="Available modes">
          {exam.modes.map((mode) => (
            <li key={mode}>
              <Badge tone="primary">{modeLabels[mode]}</Badge>
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}
