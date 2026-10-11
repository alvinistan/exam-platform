import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router';
import { Search, SearchX } from 'lucide-react';
import { Button, EmptyState, ErrorState, SelectField, Skeleton, TextField } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { ExamCard } from './ExamCard';
import { useCategories, useExams } from './api';

const PAGE_SIZE = 25;

/**
 * Browse every published exam (FR-CAT-11). Search text, category and page live in the URL, so a filtered list can
 * be bookmarked or shared.
 */
export function CataloguePage() {
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const category = params.get('category') ?? '';
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0);
  const [text, setText] = useState(q);
  const categories = useCategories();
  const exams = useExams({ q, category, page, size: PAGE_SIZE });

  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    setParams(next);
  };

  const onSearch = (e: FormEvent) => {
    e.preventDefault();
    update({ q: text.trim() || undefined, page: undefined });
  };

  const categoryName = (id: string) => categories.data?.find((c) => c.id === id)?.name;
  const total = exams.data?.page.totalElements ?? 0;
  const totalPages = exams.data?.page.totalPages ?? 0;
  const filtered = q !== '' || category !== '';

  return (
    <div className="mx-auto flex max-w-[1200px] flex-col gap-6 px-4 py-10 sm:px-8">
      <title>Exams · Leenex Labs</title>
      <div className="flex flex-col gap-1">
        <h1 className="text-3xl font-extrabold tracking-tight">Exams</h1>
        <p className="text-muted">Find your exam, then practise by topic or sit a timed mock.</p>
      </div>

      <form role="search" onSubmit={onSearch} className="grid items-end gap-3 sm:grid-cols-[1fr_220px_auto]">
        <div className="relative">
          <TextField
            label="Search exams"
            placeholder="Exam name or keyword"
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={100}
            className="pl-10"
          />
          <Search aria-hidden="true" className="pointer-events-none absolute bottom-3.5 left-3 size-4 text-muted" />
        </div>
        <SelectField
          label="Category"
          value={category}
          onChange={(e) => update({ category: e.target.value || undefined, page: undefined })}
        >
          <option value="">All categories</option>
          {categories.data?.map((c) => (
            <option key={c.id} value={c.slug}>
              {c.name}
            </option>
          ))}
        </SelectField>
        <Button type="submit" variant="secondary">
          Search
        </Button>
      </form>

      {exams.isPending ? (
        <div aria-busy="true" className="grid gap-5 [grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr))]">
          <p role="status" className="sr-only">
            Loading exams…
          </p>
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-56 w-full rounded-lg" />
          ))}
        </div>
      ) : exams.isError ? (
        <ErrorState
          title="We couldn’t load the exams"
          onRetry={() => void exams.refetch()}
          correlationId={exams.error instanceof ApiError ? exams.error.correlationId : undefined}
        />
      ) : exams.data.items.length === 0 ? (
        <EmptyState
          icon={SearchX}
          title={filtered ? 'No exams match' : 'No exams yet'}
          action={
            filtered && (
              <Button
                variant="secondary"
                onClick={() => {
                  setText('');
                  setParams(new URLSearchParams());
                }}
              >
                Clear search
              </Button>
            )
          }
        >
          <p>{filtered ? 'Try another name or category.' : 'Exams appear here as soon as they are published.'}</p>
        </EmptyState>
      ) : (
        <>
          <p className="text-sm text-muted" aria-live="polite">
            {total === 1 ? '1 exam' : `${total} exams`}
          </p>
          <ul className="grid gap-5 [grid-template-columns:repeat(auto-fill,minmax(min(280px,100%),1fr))]">
            {exams.data.items.map((exam) => (
              <li key={exam.id}>
                <ExamCard exam={exam} categoryName={categoryName(exam.categoryId)} headingLevel="h2" />
              </li>
            ))}
          </ul>
          {totalPages > 1 && (
            <nav aria-label="Pages" className="flex items-center justify-between gap-3">
              <Button
                size="sm"
                variant="secondary"
                disabled={page === 0}
                onClick={() => update({ page: page - 1 > 0 ? String(page - 1) : undefined })}
              >
                Previous
              </Button>
              <span className="text-sm text-muted">
                Page {page + 1} of {totalPages}
              </span>
              <Button
                size="sm"
                variant="secondary"
                disabled={page + 1 >= totalPages}
                onClick={() => update({ page: String(page + 1) })}
              >
                Next
              </Button>
            </nav>
          )}
        </>
      )}
    </div>
  );
}
