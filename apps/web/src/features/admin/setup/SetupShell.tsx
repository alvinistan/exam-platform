import { useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, ChevronRight, ExternalLink, TriangleAlert } from 'lucide-react';
import { Alert, Badge, Button } from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import { catalogKeys, restoreExam, type AdminExamDetail } from '@/features/admin/catalog/api';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import { STEPS, setupPath, setupStatus, type StepSlug, type StepState } from './steps';

interface Props {
  /** Undefined while a new exam is being created: only step 1 is available. */
  exam: AdminExamDetail | undefined;
  current: StepSlug;
  children: ReactNode;
}

export function ExamStatusLabel({ exam }: { exam: Pick<AdminExamDetail, 'status'> | undefined }) {
  if (!exam || exam.status === 'DRAFT') return <Badge tone="neutral">Draft · not visible to students</Badge>;
  if (exam.status === 'PUBLISHED') return <Badge tone="free">Live</Badge>;
  return <Badge tone="neutral">Archived · hidden from students</Badge>;
}

/** The frame of every setup page: breadcrumb, title, status, the five steps, and the step's content. */
export function SetupShell({ exam, current, children }: Props) {
  const location = useLocation();
  const [restored, setRestored] = useState<string | null>(null);
  const notice = (location.state as { notice?: string } | null)?.notice ?? null;
  const statuses = exam ? setupStatus(exam) : null;
  const done = statuses ? statuses.filter((s) => s.step.slug !== 'publish' && s.state === 'done').length : 0;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <nav aria-label="Breadcrumb" className="text-[14.5px] text-muted">
          <ol className="flex flex-wrap items-center gap-1">
            <li>
              <Link to="/admin/exams" className="font-semibold">
                Exams
              </Link>
            </li>
            <li aria-hidden="true">
              <ChevronRight className="size-4" />
            </li>
            <li aria-current="page">{exam ? exam.title : 'New exam'}</li>
          </ol>
        </nav>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-extrabold tracking-tight">{exam ? exam.title : 'New exam'}</h1>
            <ExamStatusLabel exam={exam} />
          </div>
          {exam?.status === 'PUBLISHED' && (
            <Link
              to={`/exams/${exam.slug}`}
              target="_blank"
              rel="noopener"
              className="inline-flex min-h-11 items-center gap-1.5 font-semibold"
            >
              View as a student <ExternalLink aria-hidden="true" className="size-4" />
              <span className="sr-only">(opens in a new tab)</span>
            </Link>
          )}
        </div>
        {exam && exam.status !== 'PUBLISHED' && (
          <p className="text-[14.5px] text-muted">
            {done} of 4 setup steps done
            {exam.publishReadiness.ready ? ' · ready to publish' : ''}
          </p>
        )}
      </div>

      {notice && <Alert tone="success" title={notice} />}
      {restored && <Alert tone="success" title={restored} />}
      {exam?.status === 'ARCHIVED' && <ArchivedBanner exam={exam} onRestored={setRestored} />}

      <div className="flex flex-col gap-5">
        <nav aria-label="Setup steps">
          <ol className="grid grid-cols-5 gap-1 rounded-lg border border-border bg-surface p-1.5 shadow-card">
            {STEPS.map((step, i) => {
              const status = statuses?.[i];
              const state: StepState | 'locked' = status ? status.state : step.slug === 'details' ? 'todo' : 'locked';
              const isCurrent = step.slug === current;
              const content = (
                <>
                  <StepMarker number={step.number} state={state} current={isCurrent} />
                  <span className="sr-only min-w-0 flex-col md:not-sr-only md:flex">
                    <span className="font-semibold text-ink">{step.title}</span>
                    <span className="truncate text-[13px] text-muted">
                      {state === 'locked' ? 'After step 1' : (status?.summary ?? step.hint)}
                    </span>
                  </span>
                </>
              );
              const className = cn(
                'flex min-h-12 items-start justify-center gap-2.5 rounded-md px-2 py-2.5 no-underline md:justify-start md:px-3',
                isCurrent ? 'bg-primary-soft' : state !== 'locked' && 'hover:bg-surface-2',
              );
              return (
                <li key={step.slug}>
                  {exam ? (
                    <Link to={setupPath(exam.id, step.slug)} aria-current={isCurrent ? 'step' : undefined} className={className}>
                      {content}
                    </Link>
                  ) : (
                    <span
                      aria-current={isCurrent ? 'step' : undefined}
                      aria-disabled={state === 'locked' || undefined}
                      className={cn(className, state === 'locked' && 'opacity-60')}
                    >
                      {content}
                    </span>
                  )}
                </li>
              );
            })}
          </ol>
        </nav>
        <div className="min-w-0 flex-1">{children}</div>
      </div>
    </div>
  );
}

function StepMarker({ number, state, current }: { number: number; state: StepState | 'locked'; current: boolean }) {
  const base = 'mt-0.5 grid size-7 flex-none place-items-center rounded-full text-[13px] font-bold';
  if (state === 'done') {
    return (
      <span className={cn(base, 'bg-success-soft text-success-ink')}>
        <Check aria-hidden="true" className="size-4" strokeWidth={3} />
        <span className="sr-only">Done:</span>
      </span>
    );
  }
  if (state === 'problem') {
    return (
      <span className={cn(base, 'bg-warning-soft text-warning-ink')}>
        <TriangleAlert aria-hidden="true" className="size-4" />
        <span className="sr-only">Needs attention:</span>
      </span>
    );
  }
  return (
    <span className={cn(base, current ? 'bg-primary text-white' : 'border-2 border-border-strong text-muted')} aria-hidden="true">
      {number}
    </span>
  );
}

function ArchivedBanner({ exam, onRestored }: { exam: AdminExamDetail; onRestored: (message: string) => void }) {
  const queryClient = useQueryClient();
  const restore = useMutation({
    mutationFn: () => restoreExam(exam.id),
    onSuccess: (saved) => {
      queryClient.setQueryData(catalogKeys.exam(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      onRestored(`${saved.title} is a draft again. Check the steps, then publish it when you’re ready.`);
    },
  });
  const error = restore.error instanceof ApiError ? restore.error.message : restore.isError ? 'Please try again.' : null;
  return (
    <Alert tone="warning" title="This exam is archived">
      <div className="flex flex-col gap-3">
        <p>Students can’t see it. Its results are kept. Restore it to edit and publish it again; its address comes back too.</p>
        <div>
          <Button variant="secondary" loading={restore.isPending} onClick={() => restore.mutate()}>
            Restore to draft
          </Button>
        </div>
        {error && <p className="text-danger-ink">{error}</p>}
      </div>
    </Alert>
  );
}

/** "Back" and "Save and continue"-style footer shared by the steps. */
export function StepFooter({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-5">{children}</div>;
}

/** Heading block at the top of each step. */
export function StepHeading({
  number,
  title,
  children,
  aside,
}: {
  number: number;
  title: string;
  children?: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="text-[13px] font-bold tracking-wider text-primary-ink uppercase">Step {number} of 5</p>
        <h2 className="mt-1 text-xl font-bold">{title}</h2>
        {children && <div className="mt-1 text-muted">{children}</div>}
      </div>
      {aside}
    </div>
  );
}
