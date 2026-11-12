import { Link } from 'react-router';
import { ArrowLeft, Receipt } from 'lucide-react';
import { Badge, Card, EmptyState, ErrorState, Skeleton } from '@/components/ui';
import { formatPrice, paymentState, useMyPayments } from '@/features/billing/api';

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' });

/** /account/payments — the student's payments with refunds, and a receipt for each one that went through. */
export function PaymentHistoryPage() {
  const payments = useMyPayments();
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-8 sm:px-6">
      <Link to="/account" className="inline-flex items-center gap-1.5 text-[14px] font-semibold">
        <ArrowLeft aria-hidden="true" className="size-4" /> Your account
      </Link>
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Payments and receipts</h1>
        <p className="text-muted">
          Everything you’ve paid for, with any refunds. Open a receipt to print it or save it as a PDF.
        </p>
      </div>
      {payments.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : payments.isError ? (
        <ErrorState title="We couldn’t load your payments" onRetry={() => void payments.refetch()} />
      ) : payments.data.length === 0 ? (
        <Card className="p-6">
          <EmptyState icon={Receipt} title="No payments yet">
            <p>
              When you buy a plan it appears here. <Link to="/plans">See plans</Link>
            </p>
          </EmptyState>
        </Card>
      ) : (
        <ul className="flex flex-col gap-3">
          {payments.data.map((p) => {
            const state = paymentState(p);
            return (
              <li key={p.id}>
                <Card className="flex flex-wrap items-start justify-between gap-3 p-5">
                  <div className="min-w-0">
                    <p className="font-semibold">{p.planName}</p>
                    <p className="text-[13.5px] text-muted">
                      {longDate.format(new Date(p.createdAt))}
                      {p.provider === 'TEST' && ' · test payment, no money moved'}
                      {p.status === 'SUCCEEDED' &&
                        p.premiumEndsAt &&
                        ` · plan until ${longDate.format(new Date(p.premiumEndsAt))}`}
                    </p>
                    {p.refunds.map((r) => (
                      <p key={r.refundedAt} className="text-[13.5px] text-ink-2">
                        {formatPrice(r.amountMinor, p.currency)} refunded on {longDate.format(new Date(r.refundedAt))}
                      </p>
                    ))}
                  </div>
                  <div className="flex flex-col items-end gap-2">
                    <span className="flex items-center gap-3">
                      <span className="font-semibold tabular-nums">{formatPrice(p.amountMinor, p.currency)}</span>
                      <Badge tone={state.tone}>{state.label}</Badge>
                    </span>
                    {p.status === 'SUCCEEDED' && (
                      <Link to={`/account/payments/${p.id}/receipt`} className="text-[14px] font-semibold">
                        Receipt
                      </Link>
                    )}
                  </div>
                </Card>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
