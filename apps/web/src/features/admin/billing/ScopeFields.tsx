import { Button, SelectField, Skeleton, TextField } from '@/components/ui';
import type { ScopeType } from '@/features/billing/api';
import { useAdminCategories } from '@/features/admin/catalog/api';
import { usePlanExams } from './api';
import { LIMIT_ROWS, type LimitKey, type LimitKind, type LimitValue } from './scope';

/** What a plan or campaign covers, and how many attempts it gives per exam type. Rules are in ./scope. */
export function ScopeFields({
  scopeType,
  categoryId,
  examIds,
  error,
  onScopeType,
  onCategory,
  onExams,
}: {
  scopeType: ScopeType;
  categoryId: string;
  examIds: string[];
  error: string | undefined;
  onScopeType: (s: ScopeType) => void;
  onCategory: (id: string) => void;
  onExams: (ids: string[]) => void;
}) {
  const exams = usePlanExams();
  const categories = useAdminCategories();
  const visibleExams = (exams.data ?? []).filter((x) => categoryId === '' || x.categoryId === categoryId);
  const toggleExam = (id: string, on: boolean) => onExams(on ? [...new Set([...examIds, id])] : examIds.filter((x) => x !== id));
  return (
    <fieldset className="flex flex-col gap-3">
      <legend className="mb-1 text-sm font-semibold">What it covers</legend>
      <div className="flex flex-wrap gap-x-5 gap-y-2" role="radiogroup" aria-label="What it covers">
        {(
          [
            ['EXAM', 'One exam'],
            ['BUNDLE', 'A bundle of exams'],
            ['ALL', 'Every exam'],
          ] as const
        ).map(([value, label]) => (
          <label key={value} className="flex items-center gap-2 text-[15px]">
            <input
              type="radio"
              name="scopeType"
              value={value}
              checked={scopeType === value}
              onChange={() => onScopeType(value)}
              className="size-4 accent-[var(--color-primary)]"
            />
            {label}
          </label>
        ))}
      </div>
      {scopeType !== 'ALL' && (
        <>
          <SelectField label="Category" value={categoryId} onChange={(ev) => onCategory(ev.target.value)}>
            <option value="">All categories</option>
            {(categories.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </SelectField>
          {exams.isPending ? (
            <Skeleton className="h-24 w-full" />
          ) : scopeType === 'EXAM' ? (
            <SelectField
              label="Exam"
              value={examIds[0] ?? ''}
              onChange={(ev) => onExams(ev.target.value ? [ev.target.value] : [])}
            >
              <option value="">Choose an exam</option>
              {visibleExams.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.title}
                  {x.status === 'DRAFT' ? ' (draft)' : ''}
                </option>
              ))}
            </SelectField>
          ) : (
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-sm font-semibold">
                  Exams in the bundle <span className="font-normal text-muted">({examIds.length} chosen)</span>
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => onExams([...new Set([...examIds, ...visibleExams.map((x) => x.id)])])}
                >
                  Choose all shown
                </Button>
              </div>
              <ul className="grid max-h-56 gap-1 overflow-y-auto rounded-md border border-border p-2 sm:grid-cols-2">
                {visibleExams.map((x) => (
                  <li key={x.id}>
                    <label className="flex items-center gap-2 rounded px-2 py-1.5 text-[14.5px] hover:bg-surface-2">
                      <input
                        type="checkbox"
                        className="size-4 accent-[var(--color-primary)]"
                        checked={examIds.includes(x.id)}
                        onChange={(ev) => toggleExam(x.id, ev.target.checked)}
                      />
                      {x.title}
                      {x.status === 'DRAFT' && <span className="text-muted">(draft)</span>}
                    </label>
                  </li>
                ))}
              </ul>
              <p className="text-[13px] text-muted">
                The bundle is a fixed list: exams added to the category later aren’t included for students who already have it.
              </p>
            </div>
          )}
          {error && (
            <p role="alert" className="text-[13.5px] font-semibold text-danger-ink">
              {error}
            </p>
          )}
        </>
      )}
    </fieldset>
  );
}

export function LimitFields({
  legend,
  values,
  errors,
  onChange,
}: {
  legend: string;
  values: Record<LimitKey, LimitValue>;
  errors: { kind?: string | undefined; counts: Partial<Record<LimitKey, string | undefined>> };
  onChange: (key: LimitKey, value: LimitValue) => void;
}) {
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-1 text-sm font-semibold">{legend}</legend>
      {LIMIT_ROWS.map(({ key, label }) => (
        <div key={key} className="grid items-start gap-3 sm:grid-cols-[140px_1fr_1fr]">
          <span className="pt-3 text-[15px] font-semibold">{label}</span>
          <SelectField
            label={`${label}: access`}
            hideLabel
            value={values[key].kind}
            onChange={(ev) => onChange(key, { ...values[key], kind: ev.target.value as LimitKind })}
          >
            <option value="UNLIMITED">Unlimited</option>
            <option value="COUNT">Number of attempts</option>
            <option value="NONE">Not included</option>
          </SelectField>
          {values[key].kind === 'COUNT' && (
            <TextField
              label={`${label}: number of attempts`}
              hideLabel
              inputMode="numeric"
              placeholder="10"
              value={values[key].count}
              error={errors.counts[key]}
              onChange={(ev) => onChange(key, { ...values[key], count: ev.target.value })}
            />
          )}
        </div>
      ))}
      {errors.kind && (
        <p role="alert" className="text-[13.5px] font-semibold text-danger-ink">
          {errors.kind}
        </p>
      )}
    </fieldset>
  );
}
