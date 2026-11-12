import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CircleCheck, TriangleAlert } from 'lucide-react';
import { Alert, Badge, Button, Card, Dialog, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { useAdminExam } from '@/features/admin/catalog/api';
import { safeReturnTo, withReturnTo } from '@/features/admin/setup/returnTo';
import { ApiError } from '@/lib/api/problem';
import { QuestionForm } from './QuestionForm';
import { QuestionStatusBadge } from './QuestionStatusBadge';
import {
  bankLabels,
  discardDraft,
  moveQuestion,
  questionKeys,
  useQuestion,
  useUserNames,
  type QuestionDetail,
  type QuestionStatus,
} from './api';

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/** Create (/admin/questions/new) or edit a question, and move it through review and publishing. */
export function QuestionEditorPage() {
  const { questionId } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const question = useQuestion(questionId);
  const returnTo = safeReturnTo(params.get('returnTo'));
  const examId = params.get('examId') ?? undefined;
  const exam = useAdminExam(examId);
  const back = returnTo ? { to: returnTo, label: 'Back to exam setup' } : { to: '/admin/questions', label: 'All questions' };
  const [raised, setRaised] = useState<{ key: string; text: string } | null>(null);
  const notice = raised?.key === location.key ? raised.text : ((location.state as { notice?: string } | null)?.notice ?? null);
  const setNotice = (text: string) => setRaised({ key: location.key, text });

  if (questionId !== undefined && question.isPending) {
    return (
      <div className="flex flex-col gap-4" aria-busy="true">
        <p role="status" className="sr-only">
          Loading question…
        </p>
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }
  if (questionId !== undefined && question.isError) {
    const error = question.error instanceof ApiError ? question.error : undefined;
    return error?.isNotFound ? (
      <EmptyState headingLevel="h1" title="Question not found" action={<Link to="/admin/questions">Back to questions</Link>} />
    ) : (
      <ErrorState title="We couldn’t load this question" onRetry={() => void question.refetch()} correlationId={error?.correlationId} />
    );
  }
  const data = question.data;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Link to={back.to} className="inline-flex items-center gap-1.5 text-[14.5px] font-semibold">
          <ArrowLeft aria-hidden="true" className="size-4" /> {back.label}
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-extrabold tracking-tight">{data ? 'Question' : 'New question'}</h1>
          {data && <QuestionStatusBadge status={data.status} />}
          {data && <Badge tone={data.bank === 'MAIN' ? 'neutral' : 'premium'}>{bankLabels[data.bank]}</Badge>}
        </div>
        {data && (
          <p className="text-[14.5px] text-muted">
            {data.topic.subjectTitle} › {data.topic.moduleTitle} › {data.topic.topicTitle}
          </p>
        )}
      </div>
      {notice && <Alert tone="success" title={notice} />}
      {data && <WorkflowPanel question={data} onDone={setNotice} />}
      <QuestionForm
        key={data ? `${data.id}-${data.version}` : 'new'}
        question={data}
        initialTopicId={params.get('topicId') ?? ''}
        limitToSubjectIds={exam.data?.subjectIds}
        onSaved={(saved, created) => {
          if (created) {
            const path = returnTo ? withReturnTo(`/admin/questions/${saved.id}`, returnTo) : `/admin/questions/${saved.id}`;
            void navigate(path, { replace: true, state: { notice: 'Draft saved. Mark it ready when it’s complete.' } });
          } else {
            setNotice(saved.status === 'PUBLISHED' && saved.hasDraftChanges
              ? 'Saved as a new version. Students keep seeing the ready version until you make the changes live.'
              : 'Saved.');
          }
        }}
      />
      {data && <AuditTrail question={data} />}
    </div>
  );
}

type Action = { to: QuestionStatus | 'DISCARD'; label: string; confirm?: string; variant?: 'primary' | 'secondary' | 'danger' };

function actionsFor(q: QuestionDetail): Action[] {
  switch (q.status) {
    case 'DRAFT':
      return [
        { to: 'PUBLISHED', label: 'Mark as ready', confirm: 'publish' },
        { to: 'REVIEWED', label: 'Mark as reviewed', variant: 'secondary' },
        { to: 'ARCHIVED', label: 'Archive', variant: 'secondary', confirm: 'archive' },
      ];
    case 'REVIEWED':
      return [
        { to: 'PUBLISHED', label: 'Mark as ready', confirm: 'publish' },
        { to: 'DRAFT', label: 'Back to draft', variant: 'secondary' },
        { to: 'ARCHIVED', label: 'Archive', variant: 'secondary', confirm: 'archive' },
      ];
    case 'PUBLISHED':
      return q.hasDraftChanges
        ? [
            { to: 'PUBLISHED', label: 'Make changes live', confirm: 'publish' },
            { to: 'DISCARD', label: 'Discard changes', variant: 'secondary', confirm: 'discard' },
            { to: 'ARCHIVED', label: 'Archive', variant: 'secondary', confirm: 'archive' },
          ]
        : [{ to: 'ARCHIVED', label: 'Archive', variant: 'secondary', confirm: 'archive' }];
    case 'ARCHIVED':
      return [{ to: 'DRAFT', label: 'Restore to draft', variant: 'secondary' }];
  }
}

const confirmText: Record<string, { title: string; body: string; button: string; variant: 'primary' | 'danger' }> = {
  publish: {
    title: 'Mark this question as ready?',
    body: 'Students can be given this version. Later edits start a new version, and students keep this one until you make the changes live.',
    button: 'Mark as ready',
    variant: 'primary',
  },
  archive: {
    title: 'Archive this question?',
    body: 'It stops being given to students. Past results that used it are kept, and you can restore it.',
    button: 'Archive',
    variant: 'danger',
  },
  discard: {
    title: 'Discard unpublished changes?',
    body: 'The question goes back to its published version. The changes can’t be recovered.',
    button: 'Discard changes',
    variant: 'danger',
  },
};

function WorkflowPanel({ question, onDone }: { question: QuestionDetail; onDone: (message: string) => void }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState<Action | null>(null);
  const move = useMutation({
    mutationFn: (a: Action) => (a.to === 'DISCARD' ? discardDraft(question.id) : moveQuestion(question.id, a.to)),
    onSuccess: (saved, a) => {
      queryClient.setQueryData(questionKeys.detail(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: questionKeys.all });
      setConfirming(null);
      onDone(
        a.to === 'DISCARD'
          ? 'Changes discarded.'
          : a.to === 'PUBLISHED'
            ? `Version ${saved.publishedVersionNo ?? saved.versionNo} is ready for students.`
            : `Moved to ${a.to.toLowerCase()}.`,
      );
    },
  });
  const run = (a: Action) => {
    move.reset();
    if (a.confirm) setConfirming(a);
    else move.mutate(a);
  };
  const error = move.error instanceof ApiError ? move.error : undefined;
  const { valid, problems } = question.validation;
  const confirm = confirming?.confirm ? confirmText[confirming.confirm] : undefined;

  return (
    <Card className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h2 className="text-lg font-bold">Status</h2>
          <p className="text-[14.5px] text-muted">
            Editing version {question.versionNo}
            {question.publishedVersionNo !== null &&
              (question.hasDraftChanges
                ? ` · students see version ${question.publishedVersionNo} until you make these changes live`
                : ' · this version is live')}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {actionsFor(question).map((a) => (
            <Button
              key={a.label}
              variant={a.variant ?? 'primary'}
              disabled={(a.to === 'REVIEWED' || a.to === 'PUBLISHED') && !valid}
              loading={move.isPending && move.variables.label === a.label}
              onClick={() => run(a)}
            >
              {a.label}
            </Button>
          ))}
        </div>
      </div>
      {question.status !== 'ARCHIVED' &&
        (valid ? (
          <p className="inline-flex items-center gap-2 text-success-ink">
            <CircleCheck aria-hidden="true" className="size-5" /> Complete: it can be marked ready.
          </p>
        ) : (
          <div className="flex flex-col gap-2 rounded-md border border-[#f2d8a6] bg-warning-soft px-4 py-3 text-warning-ink">
            <p className="inline-flex items-center gap-2 font-semibold">
              <TriangleAlert aria-hidden="true" className="size-5" /> Before it can be marked ready
            </p>
            <ul className="list-disc pl-11 text-[14.5px]">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </div>
        ))}
      {error && !confirming && (
        <Alert tone="error" title={error.code === 'PUBLISH_BLOCKED' ? 'A published exam needs this question' : 'That didn’t work'}>
          <p>{error.message}</p>
          {(error.problem?.problems ?? error.problem?.blockers) && (
            <ul className="list-disc pl-5">
              {(error.problem.problems ?? error.problem.blockers ?? []).map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          )}
        </Alert>
      )}
      <Dialog
        open={confirming !== null}
        onClose={() => setConfirming(null)}
        title={confirm?.title ?? ''}
        description={confirm?.body}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirming(null)}>
              Cancel
            </Button>
            <Button variant={confirm?.variant ?? 'primary'} loading={move.isPending} onClick={() => confirming && move.mutate(confirming)}>
              {confirm?.button}
            </Button>
          </>
        }
      >
        {error && (
          <Alert tone="error" title={error.code === 'PUBLISH_BLOCKED' ? 'A published exam needs this question' : 'That didn’t work'}>
            <p>{error.message}</p>
            {(error.problem?.blockers ?? error.problem?.problems)?.map((p) => (
              <p key={p}>{p}</p>
            ))}
          </Alert>
        )}
      </Dialog>
    </Card>
  );
}

function AuditTrail({ question }: { question: QuestionDetail }) {
  const names = useUserNames([question.createdBy, question.updatedBy, ...question.history.map((h) => h.createdBy)]);
  const who = (id: string) => names.data?.get(id) ?? 'An administrator';
  return (
    <Card className="flex flex-col gap-3 p-6">
      <h2 className="text-lg font-bold">History</h2>
      <p className="text-[14.5px] text-ink-2">
        Created by {who(question.createdBy)} on {dateTime.format(new Date(question.createdAt))}. Last changed by{' '}
        {who(question.updatedBy)} on {dateTime.format(new Date(question.updatedAt))}.
      </p>
      <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
        {question.history.map((h) => (
          <li key={h.versionNo} className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5 text-[14.5px]">
            <span className="font-semibold">Version {h.versionNo}</span>
            <span className="text-muted">
              {h.published && h.publishedAt
                ? `Published ${dateTime.format(new Date(h.publishedAt))}`
                : 'Not published'}{' '}
              · written by {who(h.createdBy)}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}
