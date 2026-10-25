import { useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { CircleCheck, FilePlus2, FileUp } from 'lucide-react';
import { Alert, Button, Card, EmptyState, ErrorState, SelectField, Skeleton, buttonClasses } from '@/components/ui';
import { examKeys, type PageMeta } from '@/features/exams/api';
import { catalogKeys, useLibrary, type AdminExamDetail } from '@/features/admin/catalog/api';
import { QuestionStatusBadge } from '@/features/admin/questions/QuestionStatusBadge';
import {
  bulkQuestions,
  difficultyLabels,
  questionKeys,
  useQuestions,
  type QuestionStatus,
  type QuestionSummary,
} from '@/features/admin/questions/api';
import { apiFetch } from '@/lib/api/client';
import { ApiError } from '@/lib/api/problem';
import { cn } from '@/lib/cn';
import { withReturnTo } from './returnTo';
import { StepFooter, StepHeading } from './SetupShell';
import { setupPath } from './steps';

type Tab = '' | 'PUBLISHED' | 'DRAFT';
const tabs: { value: Tab; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'PUBLISHED', label: 'Ready' },
  { value: 'DRAFT', label: 'Drafts' },
];

interface MarkResult {
  ready: number;
  failed: { id: string; message: string }[];
}

/** Every not-yet-ready question of the exam (drafts and reviewed), at most 500 at a time. */
async function notReadyIds(examId: string): Promise<string[]> {
  const ids: string[] = [];
  for (const status of ['DRAFT', 'REVIEWED'] as QuestionStatus[]) {
    for (let page = 0; page < 5; page++) {
      const result = await apiFetch<{
        items: QuestionSummary[];
        page: PageMeta;
      }>('/admin/catalog/questions', {
        query: {
          examId,
          status,
          bank: 'MAIN',
          page,
          size: 100,
          sort: 'updatedAt',
        },
      });
      ids.push(...result.items.map((q) => q.id));
      if (page + 1 >= result.page.totalPages) break;
    }
  }
  return ids;
}

/**
 * Marks questions ready in batches. The server applies a batch all-or-nothing, so when some questions are
 * incomplete, the rest are marked ready and the incomplete ones are reported.
 */
async function markReady(ids: string[]): Promise<MarkResult> {
  const result: MarkResult = { ready: 0, failed: [] };
  for (let i = 0; i < ids.length; i += 200) {
    const batch = ids.slice(i, i + 200);
    try {
      await bulkQuestions(batch, { action: 'SET_STATUS', status: 'PUBLISHED' });
      result.ready += batch.length;
    } catch (e) {
      const errors = e instanceof ApiError ? (e.problem?.errors ?? []) : [];
      if (errors.length === 0) throw e;
      const failed = new Set(errors.map((x) => x.field));
      result.failed.push(...errors.map((x) => ({ id: x.field, message: x.message })));
      const rest = batch.filter((id) => !failed.has(id));
      if (rest.length) {
        await bulkQuestions(rest, {
          action: 'SET_STATUS',
          status: 'PUBLISHED',
        });
        result.ready += rest.length;
      }
    }
  }
  return result;
}

/** Step 3: write or import questions, and mark them ready. Only ready questions are given to students. */
export function QuestionsStep({ exam }: { exam: AdminExamDetail }) {
  const queryClient = useQueryClient();
  const here = setupPath(exam.id, 'questions');
  const [tab, setTab] = useState<Tab>('');
  const [topicId, setTopicId] = useState('');
  const [pageState, setPageState] = useState({ key: '', page: 0 });
  const key = `${tab}|${topicId}`;
  const page = pageState.key === key ? pageState.page : 0;
  const questions = useQuestions({
    q: '',
    tag: '',
    bank: 'MAIN',
    examId: exam.id,
    subjectId: '',
    topicId,
    status: tab,
    difficulty: '',
    sort: '-updatedAt',
    page,
    size: 25,
  });
  const library = useLibrary();
  const topics = (library.data ?? [])
    .filter((s) => exam.subjectIds.includes(s.id))
    .flatMap((s) =>
      s.modules.flatMap((m) =>
        m.topics.filter((t) => t.status === 'ACTIVE').map((t) => ({ id: t.id, label: `${m.title} › ${t.title}` })),
      ),
    );

  const mark = useMutation({
    mutationFn: async () => markReady(await notReadyIds(exam.id)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: questionKeys.all });
      void queryClient.invalidateQueries({ queryKey: catalogKeys.exams() });
      void queryClient.invalidateQueries({ queryKey: catalogKeys.library() });
      void queryClient.invalidateQueries({ queryKey: examKeys.all });
    },
  });

  const needed = Math.max(0, ...exam.modeSettings.filter((m) => m.enabled).map((m) => m.minCount));
  const stems = new Map((questions.data?.items ?? []).map((q) => [q.id, q.stemText]));
  const markError = mark.error instanceof ApiError ? mark.error.message : mark.isError ? 'Please try again.' : null;
  const total = questions.data?.page.totalElements ?? 0;

  return (
    <Card className="flex flex-col gap-6 p-6 sm:p-8">
      <StepHeading number={3} title="Add questions">
        Write them one by one or import a spreadsheet. Students only get <strong className="text-ink">ready</strong> questions.
      </StepHeading>

      {exam.topicCount === 0 && (
        <Alert tone="warning" title="Add topics first">
          <p>
            Every question belongs to a topic. <Link to={setupPath(exam.id, 'subjects')}>Go to Subjects &amp; topics</Link>.
          </p>
        </Alert>
      )}

      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
        <div className="rounded-md bg-success-soft px-4 py-3">
          <dt className="text-[13px] font-semibold text-success-ink">Ready</dt>
          <dd className="text-2xl font-extrabold tabular-nums">{exam.publishedQuestionCount}</dd>
        </div>
        <div className="rounded-md bg-warning-soft px-4 py-3">
          <dt className="text-[13px] font-semibold text-warning-ink">Drafts</dt>
          <dd className="text-2xl font-extrabold tabular-nums">{exam.draftQuestionCount}</dd>
        </div>
        {needed > 0 && (
          <div className="col-span-2 rounded-md bg-surface-2 px-4 py-3 sm:col-span-1">
            <dt className="text-[13px] font-semibold text-muted">Needed by your modes</dt>
            <dd className="text-2xl font-extrabold tabular-nums">{needed}</dd>
          </div>
        )}
      </dl>

      <div className="grid gap-3 sm:grid-cols-2">
        <Link
          to={withReturnTo(`/admin/questions/new?examId=${exam.id}`, here)}
          className="flex flex-col gap-1 rounded-lg border border-border-strong p-5 text-ink no-underline hover:border-primary hover:bg-primary-soft/40"
        >
          <span className="inline-flex items-center gap-2 text-[17px] font-bold">
            <FilePlus2 aria-hidden="true" className="size-5 text-primary" /> Write a question
          </span>
          <span className="text-[14.5px] text-muted">Question, 4–5 options, the right answer and an explanation.</span>
        </Link>
        <Link
          to={withReturnTo(`/admin/questions/import?examId=${exam.id}`, here)}
          className="flex flex-col gap-1 rounded-lg border border-border-strong p-5 text-ink no-underline hover:border-primary hover:bg-primary-soft/40"
        >
          <span className="inline-flex items-center gap-2 text-[17px] font-bold">
            <FileUp aria-hidden="true" className="size-5 text-primary" /> Import from Excel or CSV
          </span>
          <span className="text-[14.5px] text-muted">Fill the template, one row per question, and upload it.</span>
        </Link>
      </div>

      {mark.isSuccess ? (
        <Alert
          tone={mark.data.failed.length ? 'warning' : 'success'}
          title={`${mark.data.ready} question${mark.data.ready === 1 ? ' is' : 's are'} now ready.`}
        >
          {mark.data.failed.length > 0 && (
            <>
              <p>{mark.data.failed.length} still need fixing before they can be used:</p>
              <ul className="list-disc pl-5">
                {mark.data.failed.map((f) => (
                  <li key={f.id}>
                    <Link to={withReturnTo(`/admin/questions/${f.id}`, here)}>{stems.get(f.id) ?? 'Open question'}</Link>
                    {' — '}
                    {f.message}
                  </li>
                ))}
              </ul>
            </>
          )}
        </Alert>
      ) : (
        exam.draftQuestionCount > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-[#f2d8a6] bg-warning-soft px-4 py-3.5 text-warning-ink">
            <p>
              <strong>
                {exam.draftQuestionCount} question
                {exam.draftQuestionCount === 1 ? ' is a draft' : 's are drafts'}.
              </strong>{' '}
              Check them, then mark them ready.
            </p>
            <Button loading={mark.isPending} onClick={() => mark.mutate()}>
              <CircleCheck aria-hidden="true" className="size-4" /> Mark all {exam.draftQuestionCount} as ready
            </Button>
          </div>
        )
      )}
      {markError && (
        <Alert tone="error" title="Questions weren’t marked ready">
          <p>{markError}</p>
        </Alert>
      )}

      <div className="flex flex-wrap items-end gap-3">
        <div role="group" aria-label="Show" className="flex flex-wrap gap-1 rounded-md bg-surface-2 p-1">
          {tabs.map((t) => (
            <button
              key={t.label}
              type="button"
              aria-pressed={tab === t.value}
              onClick={() => setTab(t.value)}
              className={cn(
                'min-h-10 rounded-sm px-4 text-[14.5px] font-semibold',
                tab === t.value ? 'bg-surface text-ink shadow-card' : 'text-muted hover:text-ink',
              )}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="min-w-56 flex-1">
          <SelectField label="Topic" value={topicId} onChange={(e) => setTopicId(e.target.value)}>
            <option value="">All topics</option>
            {topics.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label}
              </option>
            ))}
          </SelectField>
        </div>
      </div>

      {questions.isPending ? (
        <div aria-busy="true" className="flex flex-col gap-2">
          <p role="status" className="sr-only">
            Loading questions…
          </p>
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : questions.isError ? (
        <ErrorState title="We couldn’t load the questions" onRetry={() => void questions.refetch()} />
      ) : questions.data.items.length === 0 ? (
        <EmptyState title={tab || topicId ? 'No questions match' : 'No questions yet'}>
          <p>{tab || topicId ? 'Try another filter.' : 'Write one, or import a spreadsheet.'}</p>
        </EmptyState>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full min-w-[560px] border-collapse text-[14.5px]">
              <caption className="sr-only">Questions in this exam, {total} shown</caption>
              <thead>
                <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                  <th scope="col" className="px-4 py-3">
                    Question
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Topic
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Level
                  </th>
                  <th scope="col" className="px-4 py-3">
                    Status
                  </th>
                </tr>
              </thead>
              <tbody>
                {questions.data.items.map((q) => (
                  <tr key={q.id} className="border-b border-border last:border-b-0">
                    <td className="max-w-md px-4 py-3">
                      <Link to={withReturnTo(`/admin/questions/${q.id}`, here)} className="line-clamp-2 font-medium">
                        {q.stemText || 'Untitled question'}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-ink-2">
                      {q.moduleTitle} › {q.topicTitle}
                    </td>
                    <td className="px-4 py-3">{difficultyLabels[q.difficulty]}</td>
                    <td className="px-4 py-3">
                      <QuestionStatusBadge status={q.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {questions.data.page.totalPages > 1 && (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-muted" aria-live="polite">
                Page {page + 1} of {questions.data.page.totalPages}
              </p>
              <div className="flex gap-2">
                <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPageState({ key, page: page - 1 })}>
                  Previous
                </Button>
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={page + 1 >= questions.data.page.totalPages}
                  onClick={() => setPageState({ key, page: page + 1 })}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      <StepFooter>
        <Link to={setupPath(exam.id, 'subjects')} className="inline-flex min-h-11 items-center font-semibold">
          ← Back
        </Link>
        <Link to={setupPath(exam.id, 'modes')} className={buttonClasses('primary')}>
          Continue → Modes
        </Link>
      </StepFooter>
    </Card>
  );
}
