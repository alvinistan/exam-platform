import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ExternalLink, FilePlus2, Search, Sparkles } from 'lucide-react';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  SelectField,
  Skeleton,
  TextField,
  buttonClasses,
} from '@/components/ui';
import { examKeys, modeLabels } from '@/features/exams/api';
import { buildStepsDone, nextStep, setupPath, STEPS } from '@/features/admin/setup/steps';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import {
  catalogKeys,
  createSampleExam,
  restoreExam,
  useAdminCategories,
  useAdminExams,
  type AdminExam,
  type AdminExamFilters,
} from './api';

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

/** Every exam, with where each one is in its setup and the one thing to do next. */
export function ExamsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<AdminExamFilters['status']>('');
  const q = useDebounced(search.trim(), 300);
  const filterKey = JSON.stringify([q, status]);
  const [pageState, setPageState] = useState({ key: filterKey, page: 0 });
  const page = pageState.key === filterKey ? pageState.page : 0;
  const exams = useAdminExams({ q, status, categoryId: '', page });
  const categories = useAdminCategories();
  const categoryName = (id: string) => categories.data?.find((c) => c.id === id)?.name ?? '';
  const total = exams.data?.page.totalElements ?? 0;
  const filtered = q !== '' || status !== '';

  const sample = useMutation({
    mutationFn: createSampleExam,
    onSuccess: (exam) => {
      queryClient.setQueryData(catalogKeys.exam(exam.id), exam);
      void queryClient.invalidateQueries({ queryKey: catalogKeys.all });
      void navigate(setupPath(exam.id, 'publish'), {
        state: {
          notice: 'Sample exam created with 24 ready questions. Look through the steps, then publish it to try it as a student.',
        },
      });
    },
  });
  const sampleError = sample.error instanceof ApiError ? sample.error.message : sample.isError ? 'Please try again.' : null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Exams</h1>
          <p className="text-muted">Every exam is set up in 5 guided steps. Students only see it after you publish it.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" loading={sample.isPending} onClick={() => sample.mutate()}>
            <Sparkles aria-hidden="true" className="size-4" /> Create sample exam (O/L Science)
          </Button>
          <Link to="/admin/exams/new" className={buttonClasses('primary')}>
            <FilePlus2 aria-hidden="true" className="size-4" /> New exam
          </Link>
        </div>
      </div>
      {sampleError && (
        <Alert tone="error" title="The sample exam wasn’t created">
          <p>{sampleError}</p>
        </Alert>
      )}

      <section aria-label="How setup works" className="rounded-lg border border-border bg-surface px-5 py-4">
        <ol className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[14.5px] text-ink-2">
          <li className="font-semibold text-ink">How it works</li>
          {STEPS.map((s) => (
            <li key={s.slug} className="inline-flex items-center gap-2">
              <span
                className="grid size-6 place-items-center rounded-full bg-primary-soft text-[12.5px] font-bold text-primary-ink"
                aria-hidden="true"
              >
                {s.number}
              </span>
              <span>
                <span className="sr-only">Step {s.number}: </span>
                {s.title}
              </span>
            </li>
          ))}
        </ol>
      </section>

      <div className="grid gap-3 sm:grid-cols-[1fr_220px]">
        <div className="relative">
          <TextField
            label="Search exams"
            placeholder="Title or address"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-10"
            maxLength={100}
          />
          <Search aria-hidden="true" className="pointer-events-none absolute bottom-3.5 left-3 size-4 text-muted" />
        </div>
        <SelectField label="Show" value={status} onChange={(e) => setStatus(e.target.value as AdminExamFilters['status'])}>
          <option value="">All exams</option>
          <option value="DRAFT">Being set up</option>
          <option value="PUBLISHED">Live</option>
          <option value="ARCHIVED">Archived</option>
        </SelectField>
      </div>

      {exams.isPending ? (
        <div className="flex flex-col gap-3" aria-busy="true">
          <p role="status" className="sr-only">
            Loading exams…
          </p>
          {Array.from({ length: 3 }, (_, i) => (
            <Skeleton key={i} className="h-28 w-full" />
          ))}
        </div>
      ) : exams.isError ? (
        <ErrorState
          title="We couldn’t load exams"
          onRetry={() => void exams.refetch()}
          correlationId={exams.error instanceof ApiError ? exams.error.correlationId : undefined}
        />
      ) : exams.data.items.length === 0 ? (
        <EmptyState
          icon={FilePlus2}
          title={filtered ? 'No exams match' : 'No exams yet'}
          action={
            !filtered && (
              <div className="flex flex-wrap justify-center gap-2">
                <Link to="/admin/exams/new" className={buttonClasses('primary')}>
                  Create your first exam
                </Link>
                <Button variant="secondary" loading={sample.isPending} onClick={() => sample.mutate()}>
                  Or start from the sample
                </Button>
              </div>
            )
          }
        >
          <p>
            {filtered
              ? 'Try a different search or filter.'
              : 'An exam is a page students can find, with the topics, questions and modes you choose.'}
          </p>
        </EmptyState>
      ) : (
        <>
          <ul className="flex flex-col gap-3" aria-label={`Exams, ${total} in total`}>
            {exams.data.items.map((exam) => (
              <li key={exam.id}>
                <ExamRow exam={exam} categoryName={categoryName(exam.categoryId)} />
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted" aria-live="polite">
              Showing {page * 25 + 1}–{Math.min(total, (page + 1) * 25)} of {total}
            </p>
            {exams.data.page.totalPages > 1 && (
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={page === 0}
                  onClick={() => setPageState({ key: filterKey, page: page - 1 })}
                >
                  Previous
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={page + 1 >= exams.data.page.totalPages}
                  onClick={() => setPageState({ key: filterKey, page: page + 1 })}
                >
                  Next
                </Button>
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}

function ExamRow({ exam, categoryName }: { exam: AdminExam; categoryName: string }) {
  const queryClient = useQueryClient();
  const restore = useMutation({
    mutationFn: () => restoreExam(exam.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
    },
  });
  const archived = exam.status === 'ARCHIVED';
  const live = exam.status === 'PUBLISHED';
  const next = nextStep(exam);
  const done = buildStepsDone(exam);
  const headingId = `exam-${exam.id}`;

  return (
    <Card
      role="group"
      aria-labelledby={headingId}
      className={cn('flex flex-wrap items-center gap-x-6 gap-y-4 p-5', archived && 'border-dashed bg-[#fafbfc] shadow-none')}
    >
      <div className="min-w-64 flex-[2_1_18rem]">
        <div className="flex flex-wrap items-center gap-2.5">
          <h2 id={headingId} className="text-lg font-bold">
            <Link
              to={`/admin/exams/${exam.id}`}
              className={cn('no-underline', archived ? 'text-ink-2' : 'text-ink hover:text-primary')}
            >
              {exam.title}
            </Link>
          </h2>
          {live ? (
            <Badge tone="free">Live</Badge>
          ) : archived ? (
            <Badge tone="neutral">Archived</Badge>
          ) : (
            <Badge tone="warning">Being set up</Badge>
          )}
        </div>
        <p className="mt-1 text-[14px] text-muted">
          {categoryName && `${categoryName} · `}/exams/{exam.slug}
        </p>
      </div>

      {live ? (
        <dl className="flex flex-[1_1_14rem] gap-6 text-[14px]">
          <div>
            <dt className="text-muted">Ready questions</dt>
            <dd className="text-xl font-extrabold tabular-nums">{exam.publishedQuestionCount}</dd>
          </div>
          <div>
            <dt className="text-muted">Modes</dt>
            <dd className="font-semibold">{exam.modes.map((m) => modeLabels[m]).join(', ')}</dd>
          </div>
        </dl>
      ) : archived ? (
        <p className="flex-[1_1_14rem] text-[14px] text-muted">
          Hidden from students since {dateFormat.format(new Date(exam.updatedAt))}. Results are kept.
        </p>
      ) : (
        <div className="flex-[1_1_14rem]">
          <div className="mb-2 flex justify-between gap-3 text-[14px]">
            <span className="font-semibold">
              Step {next.step.number} of 5 · {next.step.title}
            </span>
            <span className="text-muted">{done} of 4 done</span>
          </div>
          <div
            role="progressbar"
            aria-label={`${exam.title} setup`}
            aria-valuemin={0}
            aria-valuemax={4}
            aria-valuenow={done}
            className="h-2 overflow-hidden rounded-full bg-surface-2"
          >
            <div className="h-full rounded-full bg-primary" style={{ width: `${(done / 4) * 100}%` }} />
          </div>
          <p className={cn('mt-2 text-[14px]', next.issues.length ? 'text-warning-ink' : 'text-muted')}>
            {next.issues[0]?.message ??
              (exam.publishReadiness.ready ? 'Ready to publish.' : `Next: ${next.step.hint.toLowerCase()}.`)}
          </p>
        </div>
      )}

      <div className="flex flex-wrap gap-2">
        {live ? (
          <>
            <Link to={`/exams/${exam.slug}`} target="_blank" rel="noopener" className={buttonClasses('secondary')}>
              View as student <ExternalLink aria-hidden="true" className="size-4" />
              <span className="sr-only">(opens in a new tab)</span>
            </Link>
            <Link to={setupPath(exam.id, 'details')} className={buttonClasses('secondary')} aria-label={`Edit ${exam.title}`}>
              Edit
            </Link>
          </>
        ) : archived ? (
          <Button
            variant="secondary"
            loading={restore.isPending}
            onClick={() => restore.mutate()}
            aria-label={`Restore to draft: ${exam.title}`}
          >
            Restore to draft
          </Button>
        ) : (
          <Link
            to={setupPath(exam.id, next.step.slug)}
            className={buttonClasses('primary')}
            aria-label={`Continue setup: ${exam.title}`}
          >
            Continue setup
          </Link>
        )}
      </div>
      {restore.isError && (
        <p className="w-full text-[14px] text-danger-ink">
          {restore.error instanceof ApiError ? restore.error.message : 'Please try again.'}
        </p>
      )}
    </Card>
  );
}
