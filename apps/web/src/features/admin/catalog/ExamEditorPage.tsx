import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CircleCheck, ExternalLink, TriangleAlert } from 'lucide-react';
import { Alert, Button, Card, Dialog, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { examKeys } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import { archiveExam, catalogKeys, publishExam, useAdminExam, type AdminExamDetail } from './api';
import { ExamDetailsForm } from './ExamDetailsForm';
import { ModeSettingsForm } from './ModeSettingsForm';
import { ExamStatusBadge } from './StatusBadge';

/** Create a draft (/admin/exams/new) or edit an exam: details, subjects, modes and publishing. */
export function ExamEditorPage() {
  const { examId } = useParams();
  const navigate = useNavigate();
  const exam = useAdminExam(examId);
  const location = useLocation();
  // A message belongs to the page view it was raised on; one can also arrive with the navigation (after "create").
  const [raised, setRaised] = useState<{ key: string; text: string } | null>(null);
  const notice =
    raised?.key === location.key ? raised.text : ((location.state as { notice?: string } | null)?.notice ?? null);
  const setNotice = (text: string) => setRaised({ key: location.key, text });

  if (examId !== undefined && exam.isPending) {
    return (
      <div className="flex flex-col gap-4" aria-busy="true">
        <p role="status" className="sr-only">
          Loading exam…
        </p>
        <Skeleton className="h-8 w-1/2" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }
  if (examId !== undefined && exam.isError) {
    const error = exam.error instanceof ApiError ? exam.error : undefined;
    return error?.isNotFound ? (
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
    );
  }

  const data = exam.data;
  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Link to="/admin/exams" className="inline-flex items-center gap-1.5 text-[14.5px] font-semibold">
          <ArrowLeft aria-hidden="true" className="size-4" /> All exams
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-extrabold tracking-tight">{data ? data.title : 'New exam'}</h1>
          {data && <ExamStatusBadge status={data.status} />}
        </div>
      </div>
      {notice && <Alert tone="success" title={notice} />}

      {data && <PublishPanel exam={data} onChanged={setNotice} />}

      <ExamDetailsForm
        key={data?.id ?? 'new'}
        exam={data}
        onCreated={(created) => {
          void navigate(`/admin/exams/${created.id}`, {
            replace: true,
            state: { notice: 'Draft created. Now choose its modes, then publish when it’s ready.' },
          });
        }}
        onSaved={() => setNotice('Details saved.')}
      />

      {data && (
        <section aria-labelledby="modes-heading" className="flex flex-col gap-4">
          <div>
            <h2 id="modes-heading" className="text-xl font-bold">
              Modes
            </h2>
            <p className="text-muted">Choose which ways of studying this exam offers.</p>
          </div>
          {data.modeSettings.map((settings) => (
            <ModeSettingsForm
              key={settings.mode}
              examId={data.id}
              settings={settings}
              publishedQuestions={data.publishedQuestionCount}
            />
          ))}
        </section>
      )}
    </div>
  );
}

function PublishPanel({ exam, onChanged }: { exam: AdminExamDetail; onChanged: (message: string) => void }) {
  const queryClient = useQueryClient();
  const [confirm, setConfirm] = useState<'publish' | 'archive' | null>(null);
  const action = useMutation({
    mutationFn: (kind: 'publish' | 'archive') => (kind === 'publish' ? publishExam(exam.id) : archiveExam(exam.id)),
    onSuccess: (saved, kind) => {
      queryClient.setQueryData(catalogKeys.exam(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
      setConfirm(null);
      onChanged(kind === 'publish' ? `${saved.title} is published.` : `${saved.title} is archived.`);
    },
  });
  const open = (kind: 'publish' | 'archive') => {
    action.reset();
    setConfirm(kind);
  };
  const published = exam.status === 'PUBLISHED';
  const { ready, blockers } = exam.publishReadiness;
  const error = action.error instanceof ApiError ? action.error : undefined;

  return (
    <Card className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h2 className="text-lg font-bold">{published ? 'Live in the catalogue' : 'Publishing'}</h2>
          <p className="text-[14.5px] text-muted">
            {exam.topicCount} active topic{exam.topicCount === 1 ? '' : 's'} · {exam.publishedQuestionCount} published
            question{exam.publishedQuestionCount === 1 ? '' : 's'}
            {exam.trialQuestionCount > 0 && ` · ${exam.trialQuestionCount} in the trial bank`}
            {exam.displayOrder !== null && ` · catalogue position ${exam.displayOrder}`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {published && (
            <Link to={`/exams/${exam.slug}`} className="inline-flex min-h-11 items-center gap-1.5 px-2 font-semibold" target="_blank" rel="noopener">
              View page <ExternalLink aria-hidden="true" className="size-4" />
              <span className="sr-only">(opens in a new tab)</span>
            </Link>
          )}
          {exam.status !== 'ARCHIVED' && (
            <Button variant="secondary" onClick={() => open('archive')}>
              Archive
            </Button>
          )}
          {!published && (
            <Button onClick={() => open('publish')} disabled={!ready}>
              Publish
            </Button>
          )}
        </div>
      </div>
      {!published &&
        (ready ? (
          <p className="inline-flex items-center gap-2 text-success-ink">
            <CircleCheck aria-hidden="true" className="size-5" /> Ready to publish.
          </p>
        ) : (
          <div className="flex flex-col gap-2 rounded-md border border-[#f2d8a6] bg-warning-soft px-4 py-3 text-warning-ink">
            <p className="inline-flex items-center gap-2 font-semibold">
              <TriangleAlert aria-hidden="true" className="size-5" /> Before you can publish
            </p>
            <ul className="list-disc pl-11 text-[14.5px]">
              {blockers.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          </div>
        ))}

      <Dialog
        open={confirm !== null}
        onClose={() => setConfirm(null)}
        title={confirm === 'publish' ? `Publish ${exam.title}?` : `Archive ${exam.title}?`}
        description={
          confirm === 'publish'
            ? `Students will find it in the catalogue and at /exams/${exam.slug} straight away.`
            : 'It leaves the catalogue and its page says it’s unavailable. Past results are kept, and you can publish it again later.'
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
    </Card>
  );
}
