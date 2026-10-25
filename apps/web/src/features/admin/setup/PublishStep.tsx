import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, GraduationCap, TriangleAlert } from 'lucide-react';
import { Alert, Badge, Button, Card, Dialog } from '@/components/ui';
import { examKeys, modeLabels } from '@/features/exams/api';
import { archiveExam, catalogKeys, publishExam, useAdminCategories, type AdminExamDetail } from '@/features/admin/catalog/api';
import { ApiError } from '@/lib/api/problem';
import { StepHeading } from './SetupShell';
import { setupPath, setupStatus, stepForIssue } from './steps';

/** Step 5: what's done, what still blocks publishing (each with a link to fix it), a preview, and archiving. */
export function PublishStep({ exam }: { exam: AdminExamDetail }) {
  const queryClient = useQueryClient();
  const categories = useAdminCategories();
  const [confirm, setConfirm] = useState<'publish' | 'archive' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const action = useMutation({
    mutationFn: (kind: 'publish' | 'archive') => (kind === 'publish' ? publishExam(exam.id) : archiveExam(exam.id)),
    onSuccess: (saved, kind) => {
      queryClient.setQueryData(catalogKeys.exam(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      setConfirm(null);
      setNotice(
        kind === 'publish'
          ? `${saved.title} is live. Students can find it at /exams/${saved.slug}.`
          : `${saved.title} is archived. You can restore it at any time.`,
      );
    },
  });
  const open = (kind: 'publish' | 'archive') => {
    action.reset();
    setConfirm(kind);
  };

  const statuses = setupStatus(exam).filter((s) => s.step.slug !== 'publish');
  const { ready, issues } = exam.publishReadiness;
  const firstIssue = issues[0];
  const published = exam.status === 'PUBLISHED';
  const archived = exam.status === 'ARCHIVED';
  const error = action.error instanceof ApiError ? action.error : undefined;
  const category = categories.data?.find((c) => c.id === exam.categoryId)?.name;

  return (
    <div className="flex flex-col gap-6">
      {notice && <Alert tone="success" title={notice} />}
      <Card className="flex flex-col gap-5 p-6 sm:p-8">
        <StepHeading number={5} title={published ? 'Live for students' : ready ? 'Ready to publish' : 'Almost there'}>
          {published
            ? 'Changes you make in the steps go live straight away, as long as the exam keeps everything it needs.'
            : ready
              ? 'Everything students need is in place.'
              : 'Fix the items below, then publish.'}
        </StepHeading>

        <ul className="flex flex-col" aria-label="Setup checklist">
          {statuses.map((s) => (
            <li key={s.step.slug} className="flex items-start gap-3 border-b border-border py-3.5 last:border-b-0">
              {s.state === 'done' ? (
                <span className="mt-0.5 grid size-7 flex-none place-items-center rounded-full bg-success-soft text-success-ink">
                  <Check aria-hidden="true" className="size-4" strokeWidth={3} />
                  <span className="sr-only">Done:</span>
                </span>
              ) : (
                <span className="mt-0.5 grid size-7 flex-none place-items-center rounded-full bg-warning-soft text-warning-ink">
                  <TriangleAlert aria-hidden="true" className="size-4" />
                  <span className="sr-only">To do:</span>
                </span>
              )}
              <div className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="font-semibold">
                  {s.step.number}. {s.step.title}
                </span>
                {s.issues.length > 0 ? (
                  s.issues.map((i) => (
                    <span key={i.message} className="text-[14.5px] text-warning-ink">
                      {i.message}
                    </span>
                  ))
                ) : (
                  <span className="text-[14.5px] text-muted">{s.state === 'done' ? s.summary : 'Not started'}</span>
                )}
              </div>
              <Link
                to={setupPath(exam.id, s.step.slug)}
                className="inline-flex min-h-11 items-center font-semibold"
                aria-label={`${s.state === 'done' ? 'Edit' : 'Fix'} ${s.step.title}`}
              >
                {s.state === 'done' ? 'Edit' : 'Fix'}
              </Link>
            </li>
          ))}
        </ul>

        {!published && !archived && (
          <div className="flex flex-wrap items-center justify-end gap-3 border-t border-border pt-5">
            {!ready && firstIssue && (
              <Link
                to={setupPath(exam.id, stepForIssue(firstIssue).slug)}
                className="mr-auto inline-flex min-h-11 items-center font-semibold"
              >
                Go to the first thing to fix
              </Link>
            )}
            <Button onClick={() => open('publish')} disabled={!ready}>
              Publish exam
            </Button>
          </div>
        )}
      </Card>

      <Card className="flex flex-col gap-3 p-6">
        <h3 className="text-[15px] font-semibold text-muted">What students will see in the catalogue</h3>
        <div className="flex flex-wrap items-center gap-4 rounded-md border border-border p-4">
          <span className="grid size-14 flex-none place-items-center rounded-md bg-primary-soft text-primary" aria-hidden="true">
            <GraduationCap className="size-7" />
          </span>
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            {category && <span className="text-[13px] font-semibold text-primary-ink">{category}</span>}
            <span className="text-lg font-bold">{exam.title}</span>
            <span className="text-[14.5px] text-muted">{exam.shortDescription || 'No short description yet.'}</span>
            <span className="flex flex-wrap gap-1.5 pt-1">
              {exam.modes.length ? (
                exam.modes.map((m) => (
                  <Badge key={m} tone="primary">
                    {modeLabels[m]}
                  </Badge>
                ))
              ) : (
                <span className="text-[13px] text-muted">No modes on yet</span>
              )}
            </span>
          </div>
        </div>
      </Card>

      {!archived && (
        <Card className="flex flex-wrap items-center justify-between gap-4 p-6">
          <div className="min-w-64 flex-1">
            <h3 className="font-bold">Archive this exam</h3>
            <p className="text-[14.5px] text-muted">
              Hides it from students and keeps their results. You can restore it to a draft at any time.
            </p>
          </div>
          <Button variant="secondary" className="text-danger-ink" onClick={() => open('archive')}>
            Archive…
          </Button>
        </Card>
      )}

      <Dialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={confirm === 'publish' ? `Publish ${exam.title}?` : `Archive ${exam.title}?`}
        description={
          confirm === 'publish'
            ? `Students will find it in the catalogue and at /exams/${exam.slug} straight away.`
            : 'It leaves the catalogue and its page says it’s unavailable. Results are kept, and you can restore it to a draft later.'
        }
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirm(null)}>
              Cancel
            </Button>
            <Button
              variant={confirm === 'archive' ? 'danger' : 'primary'}
              loading={action.isPending}
              onClick={() => confirm && action.mutate(confirm)}
            >
              {confirm === 'publish' ? 'Publish' : 'Archive'}
            </Button>
          </>
        }
      >
        {error && (
          <Alert tone="error" title={error.code === 'PUBLISH_BLOCKED' ? 'It can’t be published yet' : 'That didn’t work'}>
            {error.problem?.blockers ? (
              <ul className="list-disc pl-5">
                {error.problem.blockers.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            ) : (
              <p>{error.message}</p>
            )}
          </Alert>
        )}
      </Dialog>
    </div>
  );
}
