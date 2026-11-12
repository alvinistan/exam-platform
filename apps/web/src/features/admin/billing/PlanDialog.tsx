import { useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Trash2 } from 'lucide-react';
import { Alert, Button, CheckboxField, Dialog, SelectField, TextAreaField, TextField } from '@/components/ui';
import { billingKeys, monthsLabel } from '@/features/billing/api';
import { ApiError } from '@/lib/api/problem';
import {
  billingAdminKeys,
  createPlan,
  deletePlan,
  fromLocalInput,
  parsePrice,
  planCode,
  toLocalInput,
  updatePlan,
  type AdminPlan,
  type PlanInput,
} from './api';
import { LimitFields, ScopeFields } from './ScopeFields';
import { UNLIMITED_ALL, checkScopeAndLimits, fromLimit, limitsSchema, toLimit } from './scope';

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
    limits: limitsSchema,
    active: z.boolean(),
    displayOrder: z.number({ error: 'Enter a number.' }).int('Use a whole number.').min(0).max(1000),
    saleOn: z.boolean(),
    salePriceLkr: price,
    salePriceUsd: price,
    saleStartsAt: z.string(),
    saleEndsAt: z.string(),
  })
  .superRefine((v, ctx) => {
    if (v.priceLkr.trim() === '' && v.priceUsd.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['priceLkr'], message: 'Set an LKR price, a USD price, or both.' });
    }
    checkScopeAndLimits(v, ctx);
    if (!v.saleOn) return;
    if (v.salePriceLkr.trim() === '' && v.salePriceUsd.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['salePriceLkr'], message: 'Set a sale price in at least one currency.' });
    }
    for (const [sale, regular, field] of [
      [v.salePriceLkr, v.priceLkr, 'salePriceLkr'],
      [v.salePriceUsd, v.priceUsd, 'salePriceUsd'],
    ] as const) {
      if (sale.trim() === '') continue;
      if (regular.trim() === '') {
        ctx.addIssue({ code: 'custom', path: [field], message: 'The plan isn’t sold in this currency.' });
      } else if ((parsePrice(sale) ?? 0) >= (parsePrice(regular) ?? 0)) {
        ctx.addIssue({ code: 'custom', path: [field], message: 'Must be lower than the regular price.' });
      }
    }
    const starts = fromLocalInput(v.saleStartsAt);
    const ends = fromLocalInput(v.saleEndsAt);
    if (!starts) ctx.addIssue({ code: 'custom', path: ['saleStartsAt'], message: 'Choose when the sale starts.' });
    if (!ends) ctx.addIssue({ code: 'custom', path: ['saleEndsAt'], message: 'Choose when the sale ends.' });
    else if (starts && Date.parse(ends) <= Date.parse(starts)) {
      ctx.addIssue({ code: 'custom', path: ['saleEndsAt'], message: 'Must be after the start.' });
    }
  });
type FormValues = z.infer<typeof schema>;

const priceText = (minor: number | null) => (minor === null ? '' : (minor / 100).toFixed(2));
const orNull = (text: string) => (text.trim() === '' ? null : parsePrice(text));

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
            practice: toLimit(plan.limits.practice),
            mock: toLimit(plan.limits.mock),
            adaptive: toLimit(plan.limits.adaptive),
          },
          active: plan.active,
          displayOrder: plan.displayOrder,
          saleOn: plan.sale.endsAt !== null,
          salePriceLkr: priceText(plan.sale.priceLkrMinor),
          salePriceUsd: priceText(plan.sale.priceUsdMinor),
          saleStartsAt: toLocalInput(plan.sale.startsAt),
          saleEndsAt: toLocalInput(plan.sale.endsAt),
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
          limits: UNLIMITED_ALL,
          active: true,
          displayOrder: 0,
          saleOn: false,
          salePriceLkr: '',
          salePriceUsd: '',
          saleStartsAt: '',
          saleEndsAt: '',
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
        priceLkrMinor: orNull(v.priceLkr),
        priceUsdMinor: orNull(v.priceUsd),
        practiceLimit: fromLimit(v.limits.practice),
        mockLimit: fromLimit(v.limits.mock),
        adaptiveLimit: fromLimit(v.limits.adaptive),
        active: v.active,
        displayOrder: v.displayOrder,
        salePriceLkrMinor: v.saleOn ? orNull(v.salePriceLkr) : null,
        salePriceUsdMinor: v.saleOn ? orNull(v.salePriceUsd) : null,
        saleStartsAt: v.saleOn ? fromLocalInput(v.saleStartsAt) : null,
        saleEndsAt: v.saleOn ? fromLocalInput(v.saleEndsAt) : null,
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
  const [scopeType, categoryId, examIds, limits, saleOn] = useWatch({
    control: form.control,
    name: ['scopeType', 'categoryId', 'examIds', 'limits', 'saleOn'],
  });

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

        <ScopeFields
          scopeType={scopeType}
          categoryId={categoryId}
          examIds={examIds}
          error={e.examIds?.message}
          onScopeType={(v) => form.setValue('scopeType', v, { shouldDirty: true })}
          onCategory={(v) => form.setValue('categoryId', v, { shouldDirty: true })}
          onExams={(v) => form.setValue('examIds', v, { shouldDirty: true, shouldValidate: form.formState.isSubmitted })}
        />

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

        <LimitFields
          legend={`Attempts ${scopeType === 'BUNDLE' ? 'per subject ' : scopeType === 'ALL' ? 'per exam ' : ''}for the length of the plan`}
          values={limits}
          errors={{
            kind: e.limits?.practice?.kind?.message,
            counts: {
              practice: e.limits?.practice?.count?.message,
              mock: e.limits?.mock?.count?.message,
              adaptive: e.limits?.adaptive?.count?.message,
            },
          }}
          onChange={(key, value) =>
            form.setValue(`limits.${key}`, value, { shouldDirty: true, shouldValidate: form.formState.isSubmitted })
          }
        />

        <fieldset className="flex flex-col gap-3 rounded-md border border-border p-4">
          <legend className="px-1 text-sm font-semibold">Sale price</legend>
          <CheckboxField
            label="Sell at a lower price for a while"
            help="Pricing shows the regular price crossed out and when the sale ends."
            {...form.register('saleOn')}
          />
          {saleOn && (
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Sale price in LKR"
                inputMode="decimal"
                placeholder="2500.00"
                help="Leave empty for no LKR sale."
                error={e.salePriceLkr?.message}
                {...form.register('salePriceLkr')}
              />
              <TextField
                label="Sale price in USD"
                inputMode="decimal"
                placeholder="12.00"
                help="Leave empty for no USD sale."
                error={e.salePriceUsd?.message}
                {...form.register('salePriceUsd')}
              />
              <TextField
                label="Sale starts"
                type="datetime-local"
                error={e.saleStartsAt?.message}
                {...form.register('saleStartsAt')}
              />
              <TextField label="Sale ends" type="datetime-local" error={e.saleEndsAt?.message} {...form.register('saleEndsAt')} />
            </div>
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
