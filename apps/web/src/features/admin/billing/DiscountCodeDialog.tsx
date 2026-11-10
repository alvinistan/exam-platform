import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { Alert, Button, CheckboxField, Dialog, TextField } from '@/components/ui';
import { monthsLabel } from '@/features/billing/api';
import { ApiError } from '@/lib/api/problem';
import {
  billingAdminKeys,
  createDiscountCode,
  fromLocalInput,
  parsePrice,
  toLocalInput,
  updateDiscountCode,
  useAdminPlans,
  type DiscountCode,
  type DiscountCodeInput,
} from './api';

const amount = z.string().refine((v) => v.trim() === '' || (parsePrice(v) ?? 0) > 0, 'Enter an amount such as 500 or 500.00.');
const wholeOrEmpty = (min: number, max: number) =>
  z
    .string()
    .refine(
      (v) => v.trim() === '' || (/^\d+$/.test(v.trim()) && Number(v) >= min && Number(v) <= max),
      `Use a whole number from ${min} to ${max.toLocaleString('en-US')}.`,
    );

const schema = z
  .object({
    code: z.string(),
    description: z.string().max(200, 'Use 200 characters or fewer.'),
    kind: z.enum(['PERCENT', 'AMOUNT']),
    percentOff: z.string(),
    amountLkr: amount,
    amountUsd: amount,
    allPlans: z.boolean(),
    planIds: z.array(z.string()),
    startsAt: z.string(),
    endsAt: z.string(),
    maxUses: wholeOrEmpty(1, 1_000_000),
    maxUsesPerStudent: wholeOrEmpty(1, 100),
    active: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (
      v.kind === 'PERCENT' &&
      !(/^\d{1,3}$/.test(v.percentOff.trim()) && Number(v.percentOff) >= 1 && Number(v.percentOff) <= 100)
    ) {
      ctx.addIssue({ code: 'custom', path: ['percentOff'], message: 'Use 1 to 100.' });
    }
    if (v.kind === 'AMOUNT' && v.amountLkr.trim() === '' && v.amountUsd.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['amountLkr'], message: 'Set an amount in LKR, USD, or both.' });
    }
    if (!v.allPlans && v.planIds.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['planIds'], message: 'Choose at least one plan.' });
    }
    const ends = fromLocalInput(v.endsAt);
    const starts = fromLocalInput(v.startsAt);
    if (!ends) ctx.addIssue({ code: 'custom', path: ['endsAt'], message: 'Choose when the code stops working.' });
    else if (starts && Date.parse(ends) <= Date.parse(starts)) {
      ctx.addIssue({ code: 'custom', path: ['endsAt'], message: 'Must be after the start.' });
    }
  });
type FormValues = z.infer<typeof schema>;

const CODE = /^[A-Z0-9][A-Z0-9_-]{2,31}$/;
const priceText = (minor: number | null) => (minor === null ? '' : (minor / 100).toFixed(2));
const errorText = (error: unknown) =>
  error instanceof ApiError ? (error.problem?.errors?.[0]?.message ?? error.message) : 'Please try again.';

/** Create or edit a discount code. The code itself can't change once made; payments already made keep their discount. */
export function DiscountCodeDialog({ code, onClose }: { code: DiscountCode | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const plans = useAdminPlans();
  const form = useForm<FormValues>({
    resolver: zodResolver(
      code
        ? schema
        : schema.superRefine((v, ctx) => {
            if (!CODE.test(v.code.trim().toUpperCase())) {
              ctx.addIssue({ code: 'custom', path: ['code'], message: 'Use 3–32 letters, digits, - or _.' });
            }
          }),
    ),
    defaultValues: code
      ? {
          code: code.code,
          description: code.description,
          kind: code.percentOff !== null ? 'PERCENT' : 'AMOUNT',
          percentOff: code.percentOff === null ? '' : String(code.percentOff),
          amountLkr: priceText(code.amountOffLkrMinor),
          amountUsd: priceText(code.amountOffUsdMinor),
          allPlans: code.allPlans,
          planIds: code.planIds,
          startsAt: toLocalInput(code.startsAt),
          endsAt: toLocalInput(code.endsAt),
          maxUses: code.maxUses === null ? '' : String(code.maxUses),
          maxUsesPerStudent: String(code.maxUsesPerStudent),
          active: code.active,
        }
      : {
          code: '',
          description: '',
          kind: 'PERCENT',
          percentOff: '',
          amountLkr: '',
          amountUsd: '',
          allPlans: true,
          planIds: [],
          startsAt: toLocalInput(new Date().toISOString()),
          endsAt: '',
          maxUses: '',
          maxUsesPerStudent: '1',
          active: true,
        },
  });
  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const input: DiscountCodeInput = {
        description: v.description.trim(),
        percentOff: v.kind === 'PERCENT' ? Number(v.percentOff) : null,
        amountOffLkrMinor: v.kind === 'AMOUNT' && v.amountLkr.trim() !== '' ? parsePrice(v.amountLkr) : null,
        amountOffUsdMinor: v.kind === 'AMOUNT' && v.amountUsd.trim() !== '' ? parsePrice(v.amountUsd) : null,
        allPlans: v.allPlans,
        planIds: v.allPlans ? [] : v.planIds,
        startsAt: fromLocalInput(v.startsAt),
        endsAt: fromLocalInput(v.endsAt) ?? '',
        maxUses: v.maxUses.trim() === '' ? null : Number(v.maxUses),
        maxUsesPerStudent: v.maxUsesPerStudent.trim() === '' ? 1 : Number(v.maxUsesPerStudent),
        active: v.active,
      };
      return code ? updateDiscountCode(code, input) : createDiscountCode({ ...input, code: v.code.trim().toUpperCase() });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
      onClose();
    },
  });
  const e = form.formState.errors;
  const [kind, allPlans, planIds] = useWatch({ control: form.control, name: ['kind', 'allPlans', 'planIds'] });
  const togglePlan = (id: string, on: boolean) =>
    form.setValue('planIds', on ? [...new Set([...planIds, id])] : planIds.filter((x) => x !== id), {
      shouldValidate: form.formState.isSubmitted,
    });

  return (
    <Dialog
      open
      onClose={onClose}
      title={code ? `Edit ${code.code}` : 'New discount code'}
      description={code ? 'Payments already made with this code keep their discount.' : 'Students type the code at checkout.'}
      className="w-[min(640px,calc(100vw-32px))]"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="code-form" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save code'}
          </Button>
        </>
      }
    >
      <form id="code-form" noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))} className="flex flex-col gap-5">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="Code"
            placeholder="LAUNCH20"
            autoCapitalize="characters"
            spellCheck={false}
            readOnly={!!code}
            help={code ? 'A code can’t be changed. Make a new one instead.' : 'Letters, digits, - or _. Case doesn’t matter.'}
            className="font-mono uppercase"
            error={e.code?.message}
            {...form.register('code')}
          />
          <TextField
            label="Note (optional)"
            placeholder="Facebook launch post"
            help="Only administrators see this."
            error={e.description?.message}
            {...form.register('description')}
          />
        </div>

        <fieldset className="flex flex-col gap-3">
          <legend className="mb-1 text-sm font-semibold">Discount</legend>
          <div className="flex flex-wrap gap-x-5 gap-y-2" role="radiogroup" aria-label="Discount">
            {(
              [
                ['PERCENT', 'A percentage'],
                ['AMOUNT', 'A fixed amount'],
              ] as const
            ).map(([value, label]) => (
              <label key={value} className="flex items-center gap-2 text-[15px]">
                <input type="radio" value={value} className="size-4 accent-[var(--color-primary)]" {...form.register('kind')} />
                {label}
              </label>
            ))}
          </div>
          {kind === 'PERCENT' ? (
            <TextField
              label="Percent off"
              inputMode="numeric"
              placeholder="20"
              help="100 makes the plan free; it then starts without any payment."
              error={e.percentOff?.message}
              {...form.register('percentOff')}
            />
          ) : (
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Off LKR prices"
                inputMode="decimal"
                placeholder="500.00"
                help="Leave empty if the code isn’t for LKR payments."
                error={e.amountLkr?.message}
                {...form.register('amountLkr')}
              />
              <TextField
                label="Off USD prices"
                inputMode="decimal"
                placeholder="3.00"
                help="Leave empty if the code isn’t for USD payments."
                error={e.amountUsd?.message}
                {...form.register('amountUsd')}
              />
            </div>
          )}
        </fieldset>

        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-semibold">Plans</legend>
          <CheckboxField label="Works on every plan" {...form.register('allPlans')} />
          {!allPlans && (
            <ul className="grid max-h-48 gap-1 overflow-y-auto rounded-md border border-border p-2 sm:grid-cols-2">
              {(plans.data ?? []).map((p) => (
                <li key={p.id}>
                  <label className="flex items-center gap-2 rounded px-2 py-1.5 text-[14.5px] hover:bg-surface-2">
                    <input
                      type="checkbox"
                      className="size-4 accent-[var(--color-primary)]"
                      checked={planIds.includes(p.id)}
                      onChange={(ev) => togglePlan(p.id, ev.target.checked)}
                    />
                    {p.name}
                    <span className="text-muted">· {monthsLabel(p.durationMonths)}</span>
                  </label>
                </li>
              ))}
            </ul>
          )}
          {e.planIds?.message && (
            <p role="alert" className="text-[13.5px] font-semibold text-danger-ink">
              {e.planIds.message}
            </p>
          )}
        </fieldset>

        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Works from" type="datetime-local" error={e.startsAt?.message} {...form.register('startsAt')} />
          <TextField label="Works until" type="datetime-local" error={e.endsAt?.message} {...form.register('endsAt')} />
          <TextField
            label="Uses in total (optional)"
            inputMode="numeric"
            placeholder="No limit"
            help="Waiting and paid payments count; failed ones don’t."
            error={e.maxUses?.message}
            {...form.register('maxUses')}
          />
          <TextField
            label="Uses per student"
            inputMode="numeric"
            error={e.maxUsesPerStudent?.message}
            {...form.register('maxUsesPerStudent')}
          />
        </div>
        <CheckboxField label="Active" help="Turn off to stop the code working straight away." {...form.register('active')} />
        {save.isError && (
          <Alert tone="error" title="Couldn’t save the code">
            {errorText(save.error)}
          </Alert>
        )}
      </form>
    </Dialog>
  );
}
