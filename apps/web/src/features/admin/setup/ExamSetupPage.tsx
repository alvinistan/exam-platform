import { Link, Navigate, useNavigate, useParams } from 'react-router';
import { EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { useAdminExam } from '@/features/admin/catalog/api';
import { ApiError } from '@/lib/api/problem';
import { DetailsStep } from './DetailsStep';
import { ModesStep } from './ModesStep';
import { PublishStep } from './PublishStep';
import { QuestionsStep } from './QuestionsStep';
import { SetupShell } from './SetupShell';
import { StructureStep } from './StructureStep';
import { nextStep, setupPath, stepBySlug } from './steps';

function Loading() {
  return (
    <div className="flex flex-col gap-4" aria-busy="true">
      <p role="status" className="sr-only">
        Loading exam…
      </p>
      <Skeleton className="h-8 w-1/2" />
      <Skeleton className="h-96 w-full" />
    </div>
  );
}

function useExamOrError(examId: string | undefined) {
  const exam = useAdminExam(examId);
  if (exam.isPending) return { view: <Loading /> } as const;
  if (exam.isError) {
    const error = exam.error instanceof ApiError ? exam.error : undefined;
    return {
      view: error?.isNotFound ? (
        <EmptyState
          headingLevel="h1"
          title="Exam not found"
          action={
            <Link to="/admin/exams" className="font-semibold">
              Back to exams
            </Link>
          }
        />
      ) : (
        <ErrorState title="We couldn’t load this exam" onRetry={() => void exam.refetch()} correlationId={error?.correlationId} />
      ),
    } as const;
  }
  return { exam: exam.data } as const;
}

/** /admin/exams/new: step 1 only; the other steps open once the draft exists. */
export function NewExamPage() {
  const navigate = useNavigate();
  return (
    <SetupShell exam={undefined} current="details">
      <DetailsStep
        exam={undefined}
        onSaved={(saved) =>
          void navigate(setupPath(saved.id, 'subjects'), {
            replace: true,
            state: { notice: 'Draft created. Next, add what the exam covers.' },
          })
        }
      />
    </SetupShell>
  );
}

/** /admin/exams/:examId: opens the first step that still needs work. */
export function ExamSetupIndex() {
  const { examId } = useParams();
  const result = useExamOrError(examId);
  if (!('exam' in result)) return result.view;
  return <Navigate to={setupPath(result.exam.id, nextStep(result.exam).step.slug)} replace />;
}

/** /admin/exams/:examId/:step — one page of the guided setup. */
export function ExamSetupPage() {
  const { examId, step: stepSlug } = useParams();
  const navigate = useNavigate();
  const step = stepBySlug(stepSlug);
  const result = useExamOrError(examId);
  if (!step) return <EmptyState headingLevel="h1" title="Page not found" action={<Link to="/admin/exams">Back to exams</Link>} />;
  if (!('exam' in result)) return result.view;
  const exam = result.exam;
  const go = (slug: Parameters<typeof setupPath>[1]) => void navigate(setupPath(exam.id, slug));

  return (
    <SetupShell exam={exam} current={step.slug}>
      {step.slug === 'details' && <DetailsStep key={exam.id} exam={exam} onSaved={() => go('subjects')} />}
      {step.slug === 'subjects' && <StructureStep exam={exam} />}
      {step.slug === 'questions' && <QuestionsStep exam={exam} />}
      {step.slug === 'modes' && <ModesStep exam={exam} />}
      {step.slug === 'publish' && <PublishStep exam={exam} />}
    </SetupShell>
  );
}
