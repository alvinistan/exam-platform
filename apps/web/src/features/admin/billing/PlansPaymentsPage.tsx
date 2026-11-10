import { useState } from 'react';
import { CreditCard, Plus } from 'lucide-react';
import { Badge, Button, Card, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import type { BadgeTone } from '@/components/ui';
import { describeLimits, formatPrice, monthsLabel, type PaymentStatus } from '@/features/billing/api';
import { PlanDialog } from './PlanDialog';
import { useUserNames } from '@/features/admin/questions/api';
import { useAdminPayments, useAdminPlans, usePlanExams, type AdminPlan } from './api';

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const saleDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

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

/** /admin/plans — what Premium costs (plans on sale) and the payments students made. */
export function PlansPaymentsPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Plans &amp; payments</h1>
        <p className="text-muted">
          A plan gives attempts on one exam, a bundle, or every exam for its length. Prices, sales and attempts are yours to set;
          payments go through PayHere. Free access and discount codes are in Promotions.
        </p>
      </div>
      <PlansCard />
      <PaymentsCard />
    </div>
  );
}

function PlansCard() {
  const plans = useAdminPlans();
  const exams = usePlanExams();
  const [editing, setEditing] = useState<AdminPlan | 'new' | null>(null);
  const [now] = useState(() => Date.now());
  const titleOf = (id: string) => exams.data?.find((x) => x.id === id)?.title ?? 'Exam';
  const covers = (p: AdminPlan) =>
    p.scopeType === 'ALL'
      ? 'Every exam'
      : p.scopeType === 'EXAM'
        ? titleOf(p.examIds[0] ?? '')
        : `${p.examIds.length} exams: ${p.examIds.map(titleOf).join(', ')}`;
  return (
    <Card className="flex flex-col gap-5 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold">Plans</h2>
          <p className="text-[14.5px] text-muted">
            Sell one exam, a bundle of exams, or everything, with the attempts you choose. Changes apply to new payments only.
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
          <p>Add plans for 1, 3, 6 or 12 months: for one exam, a bundle of exams, or every exam.</p>
        </EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[820px] border-collapse text-[14.5px]">
            <caption className="sr-only">Plans</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">
                  Plan
                </th>
                <th scope="col" className="px-4 py-3">
                  Covers
                </th>
                <th scope="col" className="px-4 py-3">
                  Attempts
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
                <tr key={p.id} className="border-b border-border align-top last:border-b-0">
                  <td className="px-4 py-3">
                    <span className="font-semibold">{p.name}</span>
                    <span className="block text-[13px] text-muted">{monthsLabel(p.durationMonths)}</span>
                  </td>
                  <td className="max-w-[240px] px-4 py-3 text-ink-2">{covers(p)}</td>
                  <td className="px-4 py-3 text-[13.5px] text-ink-2">
                    {describeLimits(p.limits).map((line) => (
                      <span key={line} className="block">
                        {line}
                      </span>
                    ))}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {p.priceLkrMinor !== null && <span className="block">{formatPrice(p.priceLkrMinor, 'LKR')}</span>}
                    {p.priceUsdMinor !== null && <span className="block">{formatPrice(p.priceUsdMinor, 'USD')}</span>}
                    {p.sale.endsAt !== null && Date.parse(p.sale.endsAt) > now && (
                      <span className="mt-1 block text-[13px] text-warning-ink">
                        Sale{' '}
                        {[
                          p.sale.priceLkrMinor !== null && formatPrice(p.sale.priceLkrMinor, 'LKR'),
                          p.sale.priceUsdMinor !== null && formatPrice(p.sale.priceUsdMinor, 'USD'),
                        ]
                          .filter(Boolean)
                          .join(' · ')}
                        <span className="block text-muted">
                          {p.sale.startsAt && Date.parse(p.sale.startsAt) > now
                            ? `from ${saleDate.format(new Date(p.sale.startsAt))}`
                            : `until ${saleDate.format(new Date(p.sale.endsAt))}`}
                        </span>
                      </span>
                    )}
                  </td>
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
                  <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                    {formatPrice(p.amountMinor, p.currency)}
                    {p.discountMinor > 0 && (
                      <span className="block text-[13px] text-muted">
                        −{formatPrice(p.discountMinor, p.currency)} with a code
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <Badge tone={statusTone[p.status]}>{statusLabel[p.status]}</Badge>
                    {p.failureReason === 'CHARGEDBACK' && <span className="block text-[13px] text-danger-ink">Charged back</span>}
                    {p.provider === 'TEST' && <span className="block text-[13px] text-muted">Test payment</span>}
                    {p.provider === 'FREE' && <span className="block text-[13px] text-muted">Free with a code</span>}
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
