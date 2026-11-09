import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Trash2 } from 'lucide-react';
import { Alert, Button, CheckboxField, Dialog, SelectField, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { useAdminCategories } from '@/features/admin/catalog/api';
import { billingKeys, monthsLabel } from '@/features/billing/api';
import { ApiError } from '@/lib/api/problem';
import {
  billingAdminKeys,
  createPlan,
  deletePlan,
  parsePrice,
  planCode,
  updatePlan,
  usePlanExams,
  type AdminPlan,
  type PlanInput,
} from './api';

const LIMIT_ROWS = [
  { key: 'practice', label: 'Practice' },
  { key: 'mock', label: 'Mock exams' },
  { key: 'adaptive', label: 'Adaptive exams' },
] as const;
type LimitKey = (typeof LIMIT_ROWS)[number]['key'];
type Kind = 'UNLIMITED' | 'COUNT' | 'NONE';

const limitSchema = z.object({ kind: z.enum(['UNLIMITED', 'COUNT', 'NONE']), count: z.string() });

const price = z
  .string()
  .refine((v) => v.trim() === '' || parsePrice(v) !== null, 'Enter a price such as 3000 or 3000.00.')
  .refine((v) => v.trim() === '' || (parsePrice(v) ?? 0) >= 100, 'The price must be at least 1.00.');

const schema = z
  .object({
    name: z.string().trim().min(2, 'Use at least 2 characters.').max(80, 'Use 80 characters or fewer.'),
    description: z.string().max(500, 'Use 500 characters or fewer.'),
    scopeType: z.enum(['EXAM', 'BUNDLE', 'ALL']),
    categoryId: z.string(),
    examIds: z.array(z.string()),
    durationMonths: z.enum(['1', '3', '6', '12']),
    priceLkr: price,
    priceUsd: price,
    limits: z.object({ practice: limitSchema, mock: limitSchema, adaptive: limitSchema }),
    active: z.boolean(),
    displayOrder: z.number({ error: 'Enter a number.' }).int('Use a whole number.').min(0).max(1000),
  })
  .superRefine((v, ctx) => {
    if (v.priceLkr.trim() === '' && v.priceUsd.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['priceLkr'], message: 'Set an LKR price, a USD price, or both.' });
    }
    if (v.scopeType === 'EXAM' && v.examIds.length !== 1) {
      ctx.addIssue({ code: 'custom', path: ['examIds'], message: 'Choose the exam.' });
    }
    if (v.scopeType === 'BUNDLE' && v.examIds.length < 2) {
      ctx.addIssue({ code: 'custom', path: ['examIds'], message: 'Choose at least 2 exams for a bundle.' });
    }
    for (const { key } of LIMIT_ROWS) {
      const l = v.limits[key];
      if (l.kind === 'COUNT' && !/^\d{1,5}$/.test(l.count.trim())) {
        ctx.addIssue({ code: 'custom', path: ['limits', key, 'count'], message: 'Enter a number of attempts.' });
      } else if (l.kind === 'COUNT' && (Number(l.count) < 1 || Number(l.count) > 10000)) {
        ctx.addIssue({ code: 'custom', path: ['limits', key, 'count'], message: 'Use 1 to 10,000.' });
      }
    }
    if (LIMIT_ROWS.every(({ key }) => v.limits[key].kind === 'NONE')) {
      ctx.addIssue({ code: 'custom', path: ['limits', 'practice', 'kind'], message: 'Include at least one exam type.' });
    }
  });
type FormValues = z.infer<typeof schema>;

const toForm = (n: number | null): { kind: Kind; count: string } =>
  n === null ? { kind: 'UNLIMITED', count: '' } : n === 0 ? { kind: 'NONE', count: '' } : { kind: 'COUNT', count: String(n) };
const fromForm = (l: { kind: Kind; count: string }): number | null =>
  l.kind === 'UNLIMITED' ? null : l.kind === 'NONE' ? 0 : Number(l.count);
const priceText = (minor: number | null) => (minor === null ? '' : (minor / 100).toFixed(2));

const errorText = (error: unknown) =>
  error instanceof ApiError
    ? error.problem?.errors?.[0]
      ? `${error.problem.errors[0].field} ${error.problem.errors[0].message}`
      : error.message
    : 'Please try again.';

/**
 * Create or edit a plan: what it covers (one exam, a fixed bundle, every exam), length, LKR and USD prices, and
 * attempts per exam type. Students who already paid keep what they bought.
 */
export function PlanDialog({ plan, onClose }: { plan: AdminPlan | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const exams = usePlanExams();
  const categories = useAdminCategories();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: plan
      ? {
          name: plan.name,
          description: plan.description,
          scopeType: plan.scopeType,
          categoryId: plan.categoryId ?? '',
          examIds: plan.examIds,
          durationMonths: String(plan.durationMonths) as FormValues['durationMonths'],
          priceLkr: priceText(plan.priceLkrMinor),
          priceUsd: priceText(plan.priceUsdMinor),
          limits: {
            practice: toForm(plan.limits.practice),
            mock: toForm(plan.limits.mock),
            adaptive: toForm(plan.limits.adaptive),
          },
          active: plan.active,
          displayOrder: plan.displayOrder,
        }
      : {
          name: '',
          description: '',
          scopeType: 'EXAM',
          categoryId: '',
          examIds: [],
          durationMonths: '1',
          priceLkr: '',
          priceUsd: '',
          limits: {
            practice: { kind: 'UNLIMITED', count: '' },
            mock: { kind: 'UNLIMITED', count: '' },
            adaptive: { kind: 'UNLIMITED', count: '' },
          },
          active: true,
          displayOrder: 0,
        },
  });
  const done = () => {
    void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
    void queryClient.invalidateQueries({ queryKey: billingKeys.all });
    onClose();
  };
  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const input: PlanInput = {
        name: v.name.trim(),
        description: v.description.trim(),
        scopeType: v.scopeType,
        categoryId: v.scopeType === 'ALL' || v.categoryId === '' ? null : v.categoryId,
        examIds: v.scopeType === 'ALL' ? [] : v.examIds,
        durationMonths: Number(v.durationMonths),
        priceLkrMinor: v.priceLkr.trim() === '' ? null : parsePrice(v.priceLkr),
        priceUsdMinor: v.priceUsd.trim() === '' ? null : parsePrice(v.priceUsd),
        practiceLimit: fromForm(v.limits.practice),
        mockLimit: fromForm(v.limits.mock),
        adaptiveLimit: fromForm(v.limits.adaptive),
        active: v.active,
        displayOrder: v.displayOrder,
      };
      return plan ? updatePlan(plan, input) : createPlan({ ...input, code: planCode(input.name) });
    },
    onSuccess: done,
  });
  const remove = useMutation({
    mutationFn: () => (plan ? deletePlan(plan) : Promise.resolve(undefined)),
    onSuccess: done,
  });

  const e = form.formState.errors;
  const [scopeType, categoryId, examIds] = useWatch({
    control: form.control,
    name: ['scopeType', 'categoryId', 'examIds'],
  });
  const visibleExams = (exams.data ?? []).filter((x) => categoryId === '' || x.categoryId === categoryId);
  const toggleExam = (id: string, on: boolean) =>
    form.setValue('examIds', on ? [...new Set([...examIds, id])] : examIds.filter((x) => x !== id), { shouldValidate: true });

  return (
    <Dialog
      open
      onClose={onClose}
      title={plan ? `Edit ${plan.name}` : 'New plan'}
      description={plan ? 'Students who already paid keep the price, exams and attempts they bought.' : undefined}
      className="w-[min(720px,calc(100vw-32px))]"
      footer={
        <>
          {plan && !confirmDelete && (
            <Button variant="ghost" className="mr-auto text-danger-ink" onClick={() => setConfirmDelete(true)}>
              <Trash2 aria-hidden="true" className="size-4" /> Delete
            </Button>
          )}
          {plan && confirmDelete && (
            <Button variant="danger" className="mr-auto" onClick={() => remove.mutate()} disabled={remove.isPending}>
              {remove.isPending ? 'Deleting…' : 'Yes, delete this plan'}
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="plan-form" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save plan'}
          </Button>
        </>
      }
    >
      <form id="plan-form" noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))} className="flex flex-col gap-5">
        <TextField label="Name" placeholder="Science – 1 month" error={e.name?.message} {...form.register('name')} />

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-sm font-semibold">What it covers</legend>
          <div className="flex flex-wrap gap-x-5 gap-y-2" role="radiogroup">
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
                  value={value}
                  className="size-4 accent-[var(--color-primary)]"
                  {...form.register('scopeType')}
                />
                {label}
              </label>
            ))}
          </div>
          {scopeType !== 'ALL' && (
            <>
              {/* Controlled: the options load after the form, so a registered select would show the first option. */}
              <SelectField
                label="Category"
                value={categoryId}
                onChange={(ev) => form.setValue('categoryId', ev.target.value, { shouldDirty: true })}
              >
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
                  onChange={(ev) => form.setValue('examIds', ev.target.value ? [ev.target.value] : [], { shouldValidate: true })}
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
                      onClick={() =>
                        form.setValue('examIds', [...new Set([...examIds, ...visibleExams.map((x) => x.id)])], {
                          shouldValidate: true,
                        })
                      }
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
                    The bundle is a fixed list: exams added to the category later aren’t included for students who already bought
                    it.
                  </p>
                </div>
              )}
              {e.examIds?.message && (
                <p role="alert" className="text-[13.5px] font-semibold text-danger-ink">
                  {e.examIds.message}
                </p>
              )}
            </>
          )}
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-3">
          <SelectField label="Length" {...form.register('durationMonths')}>
            {(['1', '3', '6', '12'] as const).map((m) => (
              <option key={m} value={m}>
                {monthsLabel(Number(m))}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Price in LKR"
            inputMode="decimal"
            placeholder="3000.00"
            help="Leave empty to not sell in LKR."
            error={e.priceLkr?.message}
            {...form.register('priceLkr')}
          />
          <TextField
            label="Price in USD"
            inputMode="decimal"
            placeholder="15.00"
            help="Leave empty to not sell in USD."
            error={e.priceUsd?.message}
            {...form.register('priceUsd')}
          />
        </div>

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-semibold">
            Attempts {scopeType === 'BUNDLE' ? 'per subject ' : scopeType === 'ALL' ? 'per exam ' : ''}for the length of the plan
          </legend>
          {LIMIT_ROWS.map(({ key, label }) => (
            <LimitRow key={key} limitKey={key} label={label} form={form} />
          ))}
          {e.limits?.practice?.kind?.message && (
            <p role="alert" className="text-[13.5px] font-semibold text-danger-ink">
              {e.limits.practice.kind.message}
            </p>
          )}
        </fieldset>

        <TextAreaField
          label="Description (optional)"
          rows={2}
          help="Shown on the plan card."
          error={e.description?.message}
          {...form.register('description')}
        />
        <div className="grid gap-4 sm:grid-cols-2 sm:items-end">
          <TextField
            label="Order on the page"
            type="number"
            min={0}
            help="Lower numbers come first."
            error={e.displayOrder?.message}
            {...form.register('displayOrder', { valueAsNumber: true })}
          />
          <CheckboxField label="On sale" help="Hidden plans can’t be bought." {...form.register('active')} />
        </div>
        {(save.isError || remove.isError) && (
          <Alert tone="error" title={remove.isError ? 'Couldn’t delete the plan' : 'Couldn’t save the plan'}>
            {errorText(remove.isError ? remove.error : save.error)}
          </Alert>
        )}
      </form>
    </Dialog>
  );
}

function LimitRow({
  limitKey,
  label,
  form,
}: {
  limitKey: LimitKey;
  label: string;
  form: ReturnType<typeof useForm<FormValues>>;
}) {
  const kind = useWatch({ control: form.control, name: `limits.${limitKey}.kind` });
  const error = form.formState.errors.limits?.[limitKey]?.count?.message;
  return (
    <div className="grid items-start gap-3 sm:grid-cols-[140px_1fr_1fr]">
      <span className="pt-3 text-[15px] font-semibold">{label}</span>
      <SelectField label={`${label}: access`} hideLabel {...form.register(`limits.${limitKey}.kind`)}>
        <option value="UNLIMITED">Unlimited</option>
        <option value="COUNT">Number of attempts</option>
        <option value="NONE">Not included</option>
      </SelectField>
      {kind === 'COUNT' && (
        <TextField
          label={`${label}: number of attempts`}
          hideLabel
          inputMode="numeric"
          placeholder="10"
          error={error}
          {...form.register(`limits.${limitKey}.count`)}
        />
      )}
    </div>
  );
}
