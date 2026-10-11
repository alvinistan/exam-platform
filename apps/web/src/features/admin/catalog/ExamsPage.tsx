import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { CircleCheck, FilePlus2, Search, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, ErrorState, SelectField, Skeleton, TextField, buttonClasses } from '@/components/ui';
import { modeLabels } from '@/features/exams/api';
import { ApiError } from '@/lib/api/problem';
import { useAdminCategories, useAdminExams, type AdminExamFilters } from './api';
import { ExamStatusBadge } from './StatusBadge';

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

/** Every exam in any status, with what still stops each draft from being published (FR-CAT-05). */
export function ExamsPage() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<AdminExamFilters['status']>('');
  const [categoryId, setCategoryId] = useState('');
  const q = useDebounced(search.trim(), 300);
  const filterKey = JSON.stringify([q, status, categoryId]);
  const [pageState, setPageState] = useState({ key: filterKey, page: 0 });
  const page = pageState.key === filterKey ? pageState.page : 0;
  const exams = useAdminExams({ q, status, categoryId, page });
  const categories = useAdminCategories();
  const categoryName = (id: string) => categories.data?.find((c) => c.id === id)?.name ?? '—';
  const total = exams.data?.page.totalElements ?? 0;
  const filtered = q !== '' || status !== '' || categoryId !== '';

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Exams</h1>
          <p className="text-muted">Create exams, choose their modes and publish them when they’re ready.</p>
        </div>
        <Link to="/admin/exams/new" className={buttonClasses('primary')}>
          <FilePlus2 aria-hidden="true" className="size-4" /> New exam
        </Link>
      </div>

      <div className="grid gap-3 sm:grid-cols-[1fr_170px_200px]">
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
        <SelectField label="Status" value={status} onChange={(e) => setStatus(e.target.value as AdminExamFilters['status'])}>
          <option value="">Any status</option>
          <option value="DRAFT">Draft</option>
          <option value="PUBLISHED">Published</option>
          <option value="ARCHIVED">Archived</option>
        </SelectField>
        <SelectField label="Category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">All categories</option>
          {categories.data?.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </SelectField>
      </div>

      {exams.isPending ? (
        <div className="flex flex-col gap-2" aria-busy="true">
          <p role="status" className="sr-only">
            Loading exams…
          </p>
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-14 w-full" />
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
              <Link to="/admin/exams/new" className={buttonClasses('primary')}>
                Create the first exam
              </Link>
            )
          }
        >
          <p>{filtered ? 'Try a different search or filter.' : 'An exam is a page students can find, with the modes and topics you choose.'}</p>
        </EmptyState>
      ) : (
        <>
          <div className="relative overflow-x-auto rounded-lg border border-border bg-surface">
            <table className="w-full border-collapse text-[14.5px]">
              <caption className="sr-only">Exams, {total} in total</caption>
              <thead>
                <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                  <th scope="col" className="px-4 py-3">Exam</th>
                  <th scope="col" className="px-4 py-3">Status</th>
                  <th scope="col" className="px-4 py-3">Modes</th>
                  <th scope="col" className="px-4 py-3 text-right">Questions</th>
                  <th scope="col" className="px-4 py-3">Ready to publish</th>
                  <th scope="col" className="px-4 py-3 whitespace-nowrap">Updated</th>
                </tr>
              </thead>
              <tbody>
                {exams.data.items.map((exam) => (
                  <tr key={exam.id} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-3">
                      <Link to={`/admin/exams/${exam.id}`} className="font-semibold">
                        {exam.title}
                      </Link>
                      <p className="text-[13px] text-muted">
                        {categoryName(exam.categoryId)} · /exams/{exam.slug}
                      </p>
                    </td>
                    <td className="px-4 py-3">
                      <ExamStatusBadge status={exam.status} />
                    </td>
                    <td className="px-4 py-3 text-ink-2">
                      {exam.modes.length ? exam.modes.map((m) => modeLabels[m]).join(', ') : <span className="text-muted">None</span>}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums">{exam.publishedQuestionCount}</td>
                    <td className="px-4 py-3">
                      {exam.status === 'PUBLISHED' ? (
                        <span className="text-muted">Live</span>
                      ) : exam.publishReadiness.ready ? (
                        <span className="inline-flex items-center gap-1.5 text-success-ink">
                          <CircleCheck aria-hidden="true" className="size-4" /> Ready
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 text-warning-ink">
                          <TriangleAlert aria-hidden="true" className="size-4" />
                          {exam.publishReadiness.blockers.length === 1 ? '1 thing to fix' : `${exam.publishReadiness.blockers.length} things to fix`}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap text-muted">{dateFormat.format(new Date(exam.updatedAt))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted" aria-live="polite">
              Showing {page * 25 + 1}–{Math.min(total, (page + 1) * 25)} of {total}
            </p>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPageState({ key: filterKey, page: page - 1 })}>
                Previous
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={page + 1 >= (exams.data.page.totalPages || 1)}
                onClick={() => setPageState({ key: filterKey, page: page + 1 })}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
