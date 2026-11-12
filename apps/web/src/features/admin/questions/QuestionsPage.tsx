import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { FilePlus2, FileUp, ListChecks, Search } from 'lucide-react';
import { Alert, Badge, Button, Dialog, EmptyState, ErrorState, SelectField, Skeleton, TextField, buttonClasses } from '@/components/ui';
import { useAdminExams, useLibrary } from '@/features/admin/catalog/api';
import { ApiError } from '@/lib/api/problem';
import { QuestionStatusBadge } from './QuestionStatusBadge';
import {
  bankLabels,
  bulkQuestions,
  difficultyLabels,
  questionKeys,
  statusLabels,
  useQuestions,
  useUserNames,
  type Bank,
  type BulkAction,
  type QuestionFilters,
  type QuestionStatus,
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

type Filters = Omit<QuestionFilters, 'page' | 'q' | 'tag'>;

/** The question bank list (FR-QB-07) with bulk actions (FR-QB-09). */
export function QuestionsPage() {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [tagText, setTagText] = useState('');
  const [filters, setFilters] = useState<Filters>({
    bank: '',
    examId: '',
    subjectId: '',
    topicId: '',
    status: '',
    difficulty: '',
    sort: '-updatedAt',
    size: 25,
  });
  const q = useDebounced(search.trim(), 300);
  const tag = useDebounced(tagText.trim(), 300);
  const key = JSON.stringify([q, tag, filters]);
  const [pageState, setPageState] = useState({ key, page: 0 });
  const page = pageState.key === key ? pageState.page : 0;
  const [selection, setSelection] = useState<{ key: string; ids: string[] }>({ key, ids: [] });
  const selected = selection.key === key ? selection.ids : [];
  const setSelected = (ids: string[]) => setSelection({ key, ids });
  const questions = useQuestions({ ...filters, q, tag, page });
  const library = useLibrary();
  const exams = useAdminExams({ q: '', status: '', categoryId: '', page: 0 });
  const names = useUserNames(questions.data?.items.map((i) => i.updatedBy) ?? []);
  const [bulk, setBulk] = useState<BulkAction['action'] | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const set = <K extends keyof Filters>(k: K, v: Filters[K]) => setFilters((f) => ({ ...f, [k]: v }));
  const subjects = library.data ?? [];
  const topics = subjects
    .filter((s) => !filters.subjectId || s.id === filters.subjectId)
    .flatMap((s) => s.modules.flatMap((m) => m.topics.map((t) => ({ id: t.id, label: `${m.title} › ${t.title}` }))));
  const items = questions.data?.items ?? [];
  const allSelected = items.length > 0 && items.every((i) => selected.includes(i.id));
  const total = questions.data?.page.totalElements ?? 0;
  const filtered = q !== '' || tag !== '' || Object.entries(filters).some(([k, v]) => !['sort', 'size'].includes(k) && v !== '');

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight">Question bank</h1>
          <p className="text-muted">Every question across all exams. To work on one exam, open it from Exams (step 3).</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link to="/admin/questions/import" className={buttonClasses('secondary')}>
            <FileUp aria-hidden="true" className="size-4" /> Import
          </Link>
          <Link
            to={filters.topicId ? `/admin/questions/new?topicId=${filters.topicId}` : '/admin/questions/new'}
            className={buttonClasses('primary')}
          >
            <FilePlus2 aria-hidden="true" className="size-4" /> New question
          </Link>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="relative sm:col-span-2">
          <TextField label="Search questions" placeholder="Words in the question" value={search} onChange={(e) => setSearch(e.target.value)} className="pl-10" maxLength={200} />
          <Search aria-hidden="true" className="pointer-events-none absolute bottom-3.5 left-3 size-4 text-muted" />
        </div>
        <TextField label="Tag" value={tagText} onChange={(e) => setTagText(e.target.value)} maxLength={60} />
        <SelectField label="Exam" value={filters.examId} onChange={(e) => set('examId', e.target.value)}>
          <option value="">All exams</option>
          {exams.data?.items.map((x) => (
            <option key={x.id} value={x.id}>
              {x.title}
            </option>
          ))}
        </SelectField>
        <SelectField label="Subject" value={filters.subjectId} onChange={(e) => setFilters((f) => ({ ...f, subjectId: e.target.value, topicId: '' }))}>
          <option value="">All subjects</option>
          {subjects.map((s) => (
            <option key={s.id} value={s.id}>
              {s.title}
            </option>
          ))}
        </SelectField>
        <SelectField label="Topic" value={filters.topicId} onChange={(e) => set('topicId', e.target.value)}>
          <option value="">All topics</option>
          {topics.map((t) => (
            <option key={t.id} value={t.id}>
              {t.label}
            </option>
          ))}
        </SelectField>
        <SelectField label="Status" value={filters.status} onChange={(e) => set('status', e.target.value as Filters['status'])}>
          <option value="">Any status</option>
          {(Object.keys(statusLabels) as QuestionStatus[]).map((s) => (
            <option key={s} value={s}>
              {statusLabels[s]}
            </option>
          ))}
        </SelectField>
        <SelectField label="Difficulty" value={filters.difficulty} onChange={(e) => set('difficulty', e.target.value as Filters['difficulty'])}>
          <option value="">Any difficulty</option>
          <option value="SIMPLE">Simple</option>
          <option value="MEDIUM">Medium</option>
          <option value="HARD">Hard</option>
        </SelectField>
        <SelectField label="Bank" value={filters.bank} onChange={(e) => set('bank', e.target.value as Filters['bank'])}>
          <option value="">Both banks</option>
          <option value="MAIN">Main bank</option>
          <option value="TRIAL">Trial bank</option>
        </SelectField>
        <SelectField label="Sort by" value={filters.sort} onChange={(e) => set('sort', e.target.value as Filters['sort'])}>
          <option value="-updatedAt">Recently changed</option>
          <option value="updatedAt">Least recently changed</option>
          <option value="difficulty">Difficulty</option>
        </SelectField>
      </div>

      {notice && <Alert tone="success" title={notice} />}

      {selected.length > 0 && (
        <div role="region" aria-label="Bulk actions" className="flex flex-wrap items-center gap-2 rounded-md border border-primary bg-primary-soft px-4 py-2.5">
          <p className="mr-auto font-semibold text-primary-ink">{selected.length} selected</p>
          <Button size="sm" variant="secondary" onClick={() => setBulk('SET_STATUS')}>
            Change status
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setBulk('MOVE_TOPIC')}>
            Move to topic
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setBulk('CHANGE_BANK')}>
            Change bank
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected([])}>
            Clear
          </Button>
        </div>
      )}

      {questions.isPending ? (
        <div className="flex flex-col gap-2" aria-busy="true">
          <p role="status" className="sr-only">
            Loading questions…
          </p>
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-16 w-full" />
          ))}
        </div>
      ) : questions.isError ? (
        <ErrorState
          title="We couldn’t load questions"
          onRetry={() => void questions.refetch()}
          correlationId={questions.error instanceof ApiError ? questions.error.correlationId : undefined}
        />
      ) : items.length === 0 ? (
        <EmptyState
          icon={ListChecks}
          title={filtered ? 'No questions match' : 'No questions yet'}
          action={
            !filtered && (
              <Link to="/admin/questions/new" className={buttonClasses('primary')}>
                Write the first question
              </Link>
            )
          }
        >
          <p>{filtered ? 'Try other words or fewer filters.' : 'Write questions one by one, or import a spreadsheet.'}</p>
        </EmptyState>
      ) : (
        <>
          <div className="relative overflow-x-auto rounded-lg border border-border bg-surface">
            <table className="w-full border-collapse text-[14.5px]">
              <caption className="sr-only">Questions, {total} in total</caption>
              <thead>
                <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                  <th scope="col" className="w-10 px-4 py-3">
                    <input
                      type="checkbox"
                      aria-label="Select all on this page"
                      checked={allSelected}
                      onChange={(e) => setSelected(e.target.checked ? items.map((i) => i.id) : [])}
                      className="size-[18px] accent-primary"
                    />
                  </th>
                  <th scope="col" className="px-4 py-3">Question</th>
                  <th scope="col" className="px-4 py-3">Status</th>
                  <th scope="col" className="px-4 py-3">Difficulty</th>
                  <th scope="col" className="px-4 py-3 whitespace-nowrap">Last change</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id} className="border-b border-border align-top last:border-b-0">
                    <td className="px-4 py-3">
                      <input
                        type="checkbox"
                        aria-label={`Select “${item.stemText.slice(0, 60)}”`}
                        checked={selected.includes(item.id)}
                        onChange={(e) => setSelected(e.target.checked ? [...selected, item.id] : selected.filter((s) => s !== item.id))}
                        className="size-[18px] accent-primary"
                      />
                    </td>
                    <td className="max-w-[520px] px-4 py-3">
                      <Link to={`/admin/questions/${item.id}`} className="line-clamp-2 font-semibold">
                        {item.stemText || '(no text: image question)'}
                      </Link>
                      <p className="mt-1 text-[13px] text-muted">
                        {item.subjectTitle} › {item.moduleTitle} › {item.topicTitle}
                      </p>
                      <div className="mt-1.5 flex flex-wrap gap-1.5">
                        <Badge tone={item.bank === 'MAIN' ? 'neutral' : 'premium'}>{bankLabels[item.bank]}</Badge>
                        {item.answerType === 'MULTI' && <Badge>Multi-answer</Badge>}
                        {item.tags.map((t) => (
                          <Badge key={t}>#{t}</Badge>
                        ))}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col items-start gap-1">
                        <QuestionStatusBadge status={item.status} />
                        {item.hasDraftChanges && <span className="text-[12.5px] text-warning-ink">Changes not live yet</span>}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-ink-2">{difficultyLabels[item.difficulty]}</td>
                    <td className="px-4 py-3 whitespace-nowrap text-muted">
                      {dateFormat.format(new Date(item.updatedAt))}
                      <br />
                      <span className="text-[13px]">{names.data?.get(item.updatedBy) ?? ''}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted" aria-live="polite">
              Showing {page * filters.size + 1}–{Math.min(total, (page + 1) * filters.size)} of {total}
            </p>
            <div className="flex items-center gap-2">
              <SelectField label="Per page" hideLabel value={String(filters.size)} onChange={(e) => set('size', Number(e.target.value) as Filters['size'])} className="min-h-9 py-1.5">
                <option value="25">25 per page</option>
                <option value="50">50 per page</option>
                <option value="100">100 per page</option>
              </SelectField>
              <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPageState({ key, page: page - 1 })}>
                Previous
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={page + 1 >= (questions.data.page.totalPages || 1)}
                onClick={() => setPageState({ key, page: page + 1 })}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}

      {bulk && (
        <BulkDialog
          action={bulk}
          count={selected.length}
          topics={subjects.flatMap((s) =>
            s.status === 'ACTIVE'
              ? s.modules
                  .filter((m) => m.status === 'ACTIVE')
                  .flatMap((m) => m.topics.filter((t) => t.status === 'ACTIVE').map((t) => ({ id: t.id, label: `${s.title} › ${m.title} › ${t.title}` })))
              : [],
          )}
          onClose={() => setBulk(null)}
          onConfirm={async (action) => {
            const result = await bulkQuestions(selected, action);
            setBulk(null);
            setSelected([]);
            setNotice(
              action.action === 'CHANGE_BANK'
                ? `${result.updated} question(s) copied to the ${bankLabels[action.bank].toLowerCase()}; the originals are archived.`
                : `${result.updated} question(s) updated.`,
            );
            await queryClient.invalidateQueries({ queryKey: questionKeys.all });
          }}
        />
      )}
    </div>
  );
}

function BulkDialog({
  action,
  count,
  topics,
  onClose,
  onConfirm,
}: {
  action: BulkAction['action'];
  count: number;
  topics: { id: string; label: string }[];
  onClose: () => void;
  onConfirm: (a: BulkAction) => Promise<void>;
}) {
  const [status, setStatus] = useState<QuestionStatus>('REVIEWED');
  const [topicId, setTopicId] = useState('');
  const [bank, setBank] = useState<Bank>('TRIAL');
  const run = useMutation({
    mutationFn: () =>
      onConfirm(
        action === 'SET_STATUS'
          ? { action, status }
          : action === 'MOVE_TOPIC'
            ? { action, topicId }
            : { action, bank },
      ),
  });
  const error = run.error instanceof ApiError ? run.error : undefined;
  const title =
    action === 'SET_STATUS' ? `Change the status of ${count} question(s)` : action === 'MOVE_TOPIC' ? `Move ${count} question(s)` : `Change the bank of ${count} question(s)`;
  return (
    <Dialog
      open
      onClose={onClose}
      title={title}
      description="All selected questions change, or none do. The change is recorded in the audit log."
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button loading={run.isPending} disabled={action === 'MOVE_TOPIC' && !topicId} onClick={() => run.mutate()}>
            Apply to {count}
          </Button>
        </>
      }
    >
      {action === 'SET_STATUS' && (
        <SelectField label="New status" value={status} onChange={(e) => setStatus(e.target.value as QuestionStatus)}>
          {(Object.keys(statusLabels) as QuestionStatus[]).map((s) => (
            <option key={s} value={s}>
              {statusLabels[s]}
            </option>
          ))}
        </SelectField>
      )}
      {action === 'MOVE_TOPIC' && (
        <SelectField label="Topic" value={topicId} onChange={(e) => setTopicId(e.target.value)}>
          <option value="">Choose a topic</option>
          {topics.map((t) => (
            <option key={t.id} value={t.id}>
              {t.label}
            </option>
          ))}
        </SelectField>
      )}
      {action === 'CHANGE_BANK' && (
        <>
          <SelectField label="Move to" value={bank} onChange={(e) => setBank(e.target.value as Bank)}>
            <option value="TRIAL">Trial bank</option>
            <option value="MAIN">Main bank</option>
          </SelectField>
          <p className="text-[14px] text-muted">Each question is copied into that bank and the original is archived, so past results keep pointing at it.</p>
        </>
      )}
      {error && (
        <Alert tone="error" title="Nothing was changed">
          <p>{error.message}</p>
          {error.problem?.errors && (
            <ul className="list-disc pl-5">
              {error.problem.errors.slice(0, 8).map((e) => (
                <li key={e.field}>{e.message}</li>
              ))}
            </ul>
          )}
        </Alert>
      )}
    </Dialog>
  );
}
