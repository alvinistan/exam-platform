import { Link, useParams } from 'react-router';
import { ArrowLeft, Printer } from 'lucide-react';
import { Button, Card, ErrorState, Skeleton } from '@/components/ui';
import logoMark from '@/assets/brand/logo-mark.webp';
import { formatPrice, monthsLabel, paymentState, usePayment, type Payment } from '@/features/billing/api';
import { useAuth } from '@/lib/auth/AuthContext';

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' });
const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'long', timeStyle: 'short' });

/** /account/payments/:id/receipt — a printable receipt for one of the student's own payments that went through. */
export function ReceiptPage() {
  const { transactionId = '' } = useParams();
  const payment = usePayment(transactionId);
  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5 px-4 py-8 sm:px-6 print:max-w-none print:p-0">
      <div className="flex flex-wrap items-center justify-between gap-3 print:hidden">
        <Link to="/account/payments" className="inline-flex items-center gap-1.5 text-[14px] font-semibold">
          <ArrowLeft aria-hidden="true" className="size-4" /> Payments and receipts
        </Link>
        {payment.data?.status === 'SUCCEEDED' && (
          <Button variant="secondary" onClick={() => window.print()}>
            <Printer aria-hidden="true" className="size-4" /> Print or save as PDF
          </Button>
        )}
      </div>
      {payment.isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : payment.isError ? (
        <ErrorState title="We couldn’t find this payment" />
      ) : payment.data.status !== 'SUCCEEDED' ? (
        <Card className="p-6">
          <p>This payment didn’t go through, so there’s no receipt for it.</p>
        </Card>
      ) : (
        <Receipt payment={payment.data} />
      )}
    </div>
  );
}

function Receipt({ payment: p }: { payment: Payment }) {
  const { state } = useAuth();
  const user = state.status === 'signedIn' ? state.user : null;
  const list = p.listPriceMinor ?? p.amountMinor + p.discountMinor;
  const saleSaving = list - p.discountMinor - p.amountMinor;
  const status = paymentState(p);
  return (
    <Card className="flex flex-col gap-6 p-6 sm:p-8 print:border-0 print:shadow-none">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-center gap-2.5">
          <img src={logoMark} alt="" width={36} height={36} className="size-9 object-contain" />
          <span className="text-xl font-extrabold tracking-tight">Leenex Labs</span>
        </div>
        <div className="text-right">
          <h1 className="text-2xl font-extrabold tracking-tight">Receipt</h1>
          <p className="text-[13.5px] text-muted">{dateTime.format(new Date(p.createdAt))}</p>
        </div>
      </header>

      <dl className="grid gap-x-6 gap-y-1.5 text-[14.5px] sm:grid-cols-[170px_1fr]">
        {user && (
          <>
            <dt className="text-muted">Billed to</dt>
            <dd>
              {user.displayName}
              <span className="block text-muted">{user.email}</span>
            </dd>
          </>
        )}
        <dt className="text-muted">Receipt number</dt>
        <dd className="font-mono text-[13.5px] break-all">{p.id}</dd>
        <dt className="text-muted">Paid with</dt>
        <dd>
          {p.provider === 'PAYHERE'
            ? `PayHere${p.paymentRef ? ` · payment ${p.paymentRef}` : ''}`
            : p.provider === 'FREE'
              ? 'Discount code (nothing to pay)'
              : 'Test payment (no money moved)'}
        </dd>
        <dt className="text-muted">Status</dt>
        <dd>{status.label}</dd>
      </dl>

      <table className="w-full border-collapse text-[14.5px]">
        <caption className="sr-only">What was paid</caption>
        <thead>
          <tr className="border-b border-border-strong text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
            <th scope="col" className="py-2">
              Item
            </th>
            <th scope="col" className="py-2 text-right">
              Amount
            </th>
          </tr>
        </thead>
        <tbody>
          <tr className="border-b border-border">
            <td className="py-2.5">
              {p.planName}
              <span className="block text-[13px] text-muted">
                {monthsLabel(p.durationMonths)}
                {p.premiumStartsAt &&
                  p.premiumEndsAt &&
                  `, ${longDate.format(new Date(p.premiumStartsAt))} – ${longDate.format(new Date(p.premiumEndsAt))}`}
              </span>
            </td>
            <td className="py-2.5 text-right tabular-nums">{formatPrice(list, p.currency)}</td>
          </tr>
          {saleSaving > 0 && (
            <tr className="border-b border-border">
              <td className="py-2.5">Sale price</td>
              <td className="py-2.5 text-right tabular-nums">−{formatPrice(saleSaving, p.currency)}</td>
            </tr>
          )}
          {p.discountMinor > 0 && (
            <tr className="border-b border-border">
              <td className="py-2.5">Discount code</td>
              <td className="py-2.5 text-right tabular-nums">−{formatPrice(p.discountMinor, p.currency)}</td>
            </tr>
          )}
          <tr>
            <th scope="row" className="pt-3 text-left font-bold">
              Paid
            </th>
            <td className="pt-3 text-right text-lg font-extrabold tabular-nums">{formatPrice(p.amountMinor, p.currency)}</td>
          </tr>
          {p.refunds.map((r) => (
            <tr key={r.refundedAt}>
              <td className="pt-1.5 text-ink-2">Refunded on {longDate.format(new Date(r.refundedAt))}</td>
              <td className="pt-1.5 text-right tabular-nums text-ink-2">−{formatPrice(r.amountMinor, p.currency)}</td>
            </tr>
          ))}
          {p.refundedMinor > 0 && (
            <tr>
              <th scope="row" className="pt-2 text-left font-bold">
                Net paid
              </th>
              <td className="pt-2 text-right font-bold tabular-nums">
                {formatPrice(p.amountMinor - p.refundedMinor, p.currency)}
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <p className="text-[12.5px] text-muted">Thank you for learning with Leenex Labs. Keep this receipt for your records.</p>
    </Card>
  );
}
