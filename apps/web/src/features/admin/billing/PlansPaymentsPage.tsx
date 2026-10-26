import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { z } from 'zod';
import { CreditCard, Plus } from 'lucide-react';
import {
  Alert,
  Badge,
  Button,
  Card,
  CheckboxField,
  Dialog,
  EmptyState,
  ErrorState,
  SelectField,
  Skeleton,
  TextAreaField,
  TextField,
} from '@/components/ui';
import type { BadgeTone } from '@/components/ui';
import { billingKeys, formatPrice, monthsLabel, type PaymentStatus } from '@/features/billing/api';
import { useUserNames } from '@/features/admin/questions/api';
import { ApiError } from '@/lib/api/problem';
import {
  billingAdminKeys,
  createPlan,
  parsePrice,
  planCode,
  updatePlan,
  useAdminPayments,
  useAdminPlans,
  type AdminPlan,
  type PlanInput,
} from './api';

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });

const statusTone: Record<PaymentStatus, BadgeTone> = {
  SUCCEEDED: 'free',
  PENDING: 'warning',
  FAILED: 'danger',
  CANCELLED: 'neutral',
};
const statusLabel: Record<PaymentStatus, string> = {
  SUCCEEDED: 'Paid',
  PENDING: 'Waiting',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

const errorText = (error: unknown) =>
  error instanceof ApiError
    ? error.problem?.errors?.[0]
      ? `${error.problem.errors[0].field} ${error.problem.errors[0].message}`
      : error.message
    : 'Please try again.';

/** /admin/plans — what Premium costs (plans on sale) and the payments students made. */
export function PlansPaymentsPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Plans &amp; payments</h1>
        <p className="text-muted">
          Premium gives a student unlimited attempts on every exam for the length of their plan. Prices are yours to set; payments
          go through PayHere.
        </p>
      </div>
      <PlansCard />
      <PaymentsCard />
    </div>
  );
}

function PlansCard() {
  const plans = useAdminPlans();
  const [editing, setEditing] = useState<AdminPlan | 'new' | null>(null);
  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold">Plans</h2>
          <p className="text-[14.5px] text-muted">
            Plans on sale appear on the Premium page in this order. A price change applies to new payments only.
          </p>
        </div>
        <Button onClick={() => setEditing('new')}>
          <Plus aria-hidden="true" className="size-4" />
          New plan
        </Button>
      </div>
      {plans.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : plans.isError ? (
        <ErrorState title="We couldn’t load the plans" onRetry={() => void plans.refetch()} />
      ) : plans.data.length === 0 ? (
        <EmptyState icon={CreditCard} title="No plans yet">
          <p>Add plans for 1, 3, 6 or 12 months. Students can buy Premium once a plan is on sale.</p>
        </EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[620px] border-collapse text-[14.5px]">
            <caption className="sr-only">Plans</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">
                  Plan
                </th>
                <th scope="col" className="px-4 py-3">
                  Length
                </th>
                <th scope="col" className="px-4 py-3">
                  Price
                </th>
                <th scope="col" className="px-4 py-3">
                  Status
                </th>
                <th scope="col" className="px-4 py-3">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {plans.data.map((p) => (
                <tr key={p.id} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-3">
                    <span className="font-semibold">{p.name}</span>
                    {p.description && <span className="block text-[13px] text-muted">{p.description}</span>}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">{monthsLabel(p.durationMonths)}</td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">{formatPrice(p.priceMinor, p.currency)}</td>
                  <td className="px-4 py-3">
                    {p.active ? <Badge tone="free">On sale</Badge> : <Badge tone="neutral">Hidden</Badge>}
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Button variant="ghost" size="sm" onClick={() => setEditing(p)} aria-label={`Edit ${p.name}`}>
                      Edit
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <PlanDialog plan={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

const schema = z.object({
  name: z.string().trim().min(2, 'Use at least 2 characters.').max(80, 'Use 80 characters or fewer.'),
  description: z.string().max(500, 'Use 500 characters or fewer.'),
  durationMonths: z.enum(['1', '3', '6', '12']),
  price: z
    .string()
    .refine((v) => parsePrice(v) !== null, 'Enter a price such as 4500 or 4500.00.')
    .refine((v) => (parsePrice(v) ?? 0) >= 100, 'The price must be at least 1.00.'),
  currency: z.enum(['LKR', 'USD']),
  active: z.boolean(),
  displayOrder: z.number({ error: 'Enter a number.' }).int('Use a whole number.').min(0).max(1000),
});
type FormValues = z.infer<typeof schema>;

function PlanDialog({ plan, onClose }: { plan: AdminPlan | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: plan
      ? {
          name: plan.name,
          description: plan.description,
          durationMonths: String(plan.durationMonths) as FormValues['durationMonths'],
          price: (plan.priceMinor / 100).toFixed(2),
          currency: plan.currency,
          active: plan.active,
          displayOrder: plan.displayOrder,
        }
      : { name: '', description: '', durationMonths: '1', price: '', currency: 'LKR', active: true, displayOrder: 0 },
  });
  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const input: PlanInput = {
        name: v.name.trim(),
        description: v.description.trim(),
        durationMonths: Number(v.durationMonths),
        priceMinor: parsePrice(v.price) ?? 0,
        currency: v.currency,
        active: v.active,
        displayOrder: v.displayOrder,
      };
      return plan ? updatePlan(plan, input) : createPlan({ ...input, code: planCode(input.name) });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
      void queryClient.invalidateQueries({ queryKey: billingKeys.all });
      onClose();
    },
  });
  const e = form.formState.errors;
  return (
    <Dialog
      open
      onClose={onClose}
      title={plan ? `Edit ${plan.name}` : 'New plan'}
      description={plan ? 'Students who already paid keep the price and length they bought.' : undefined}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" form="plan-form" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save plan'}
          </Button>
        </>
      }
    >
      <form id="plan-form" noValidate onSubmit={form.handleSubmit((v) => save.mutate(v))} className="flex flex-col gap-4">
        <TextField label="Name" placeholder="3 months" error={e.name?.message} {...form.register('name')} />
        <div className="grid gap-4 sm:grid-cols-3">
          <SelectField label="Length" {...form.register('durationMonths')}>
            {(['1', '3', '6', '12'] as const).map((m) => (
              <option key={m} value={m}>
                {monthsLabel(Number(m))}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Price"
            inputMode="decimal"
            placeholder="4500.00"
            error={e.price?.message}
            {...form.register('price')}
          />
          <SelectField label="Currency" {...form.register('currency')}>
            <option value="LKR">LKR</option>
            <option value="USD">USD</option>
          </SelectField>
        </div>
        <TextAreaField
          label="Description (optional)"
          rows={2}
          help="Shown under the price on the Premium page."
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
        {save.isError && (
          <Alert tone="error" title="Couldn’t save the plan">
            {errorText(save.error)}
          </Alert>
        )}
      </form>
    </Dialog>
  );
}

function PaymentsCard() {
  const payments = useAdminPayments();
  const names = useUserNames((payments.data ?? []).map((p) => p.studentId));
  return (
    <Card className="flex flex-col gap-5 p-6">
      <div>
        <h2 className="text-lg font-bold">Payments</h2>
        <p className="text-[14.5px] text-muted">
          Newest first. A payment counts only when PayHere confirms it to the server; “Waiting” means no confirmation yet.
        </p>
      </div>
      {payments.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : payments.isError ? (
        <ErrorState title="We couldn’t load the payments" onRetry={() => void payments.refetch()} />
      ) : payments.data.length === 0 ? (
        <EmptyState title="No payments yet">
          <p>Payments appear here as soon as a student starts checking out.</p>
        </EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[720px] border-collapse text-[14.5px]">
            <caption className="sr-only">Payments</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">
                  Date
                </th>
                <th scope="col" className="px-4 py-3">
                  Student
                </th>
                <th scope="col" className="px-4 py-3">
                  Plan
                </th>
                <th scope="col" className="px-4 py-3">
                  Amount
                </th>
                <th scope="col" className="px-4 py-3">
                  Status
                </th>
                <th scope="col" className="px-4 py-3">
                  PayHere reference
                </th>
              </tr>
            </thead>
            <tbody>
              {payments.data.map((p) => (
                <tr key={p.id} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-3 whitespace-nowrap">{dateTime.format(new Date(p.createdAt))}</td>
                  <td className="px-4 py-3 font-semibold">{names.data?.get(p.studentId) ?? 'Unknown user'}</td>
                  <td className="px-4 py-3">{p.planName}</td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">{formatPrice(p.amountMinor, p.currency)}</td>
                  <td className="px-4 py-3">
                    <Badge tone={statusTone[p.status]}>{statusLabel[p.status]}</Badge>
                    {p.failureReason === 'CHARGEDBACK' && <span className="block text-[13px] text-danger-ink">Charged back</span>}
                    {p.provider === 'TEST' && <span className="block text-[13px] text-muted">Test payment</span>}
                  </td>
                  <td className="px-4 py-3 font-mono text-[13px] text-ink-2">
                    {p.providerRef && p.provider === 'PAYHERE' ? p.providerRef : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
