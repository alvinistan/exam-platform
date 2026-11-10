import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Badge, Button, CheckboxField, Dialog, ErrorState, Skeleton, TextAreaField, TextField } from '@/components/ui';
import { accessKeys } from '@/features/access/api';
import { formatPrice, monthsLabel } from '@/features/billing/api';
import { billingAdminKeys, parsePrice } from '@/features/admin/billing/api';
import { ApiError } from '@/lib/api/problem';
import {
  financeKeys,
  namesFor,
  paymentStateLabel,
  refundPayment,
  resolveRefund,
  stateTone,
  usePaymentDetail,
  type PaymentDetail,
  type Refund,
  type RefundInput,
} from './api';

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const day = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

const errorText = (e: unknown) =>
  e instanceof ApiError ? (e.problem?.errors?.[0] ? `${e.problem.errors[0].message}.` : e.message) : 'Please try again.';

/** One payment: what was bought, what was paid, its refunds, and a refund form when something is left to refund. */
export function PaymentDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const detail = usePaymentDetail(id);
  const studentId = detail.data?.payment.studentId;
  const who = useQuery({
    queryKey: ['admin-finance', 'names', [studentId]],
    queryFn: () => namesFor(studentId ? [studentId] : []),
    enabled: !!studentId,
    staleTime: 5 * 60_000,
  });
  const person = studentId ? who.data?.get(studentId) : undefined;
  return (
    <Dialog
      open
      onClose={onClose}
      title="Payment"
      description={detail.data ? dateTime.format(new Date(detail.data.payment.createdAt)) : undefined}
      className="w-[min(720px,calc(100vw-32px))]"
      footer={
        <Button variant="ghost" onClick={onClose}>
          Close
        </Button>
      }
    >
      {detail.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : detail.isError ? (
        <ErrorState title="We couldn’t load this payment" onRetry={() => void detail.refetch()} />
      ) : (
        <div className="flex flex-col gap-6">
          <Facts detail={detail.data} person={person} />
          <Refunds refunds={detail.data.refunds} />
          {detail.data.refundableMinor > 0 && <RefundForm key={detail.data.refundableMinor} detail={detail.data} />}
        </div>
      )}
    </Dialog>
  );
}

function Facts({ detail, person }: { detail: PaymentDetail; person: { displayName: string; email: string } | undefined }) {
  const p = detail.payment;
  const rows: [string, React.ReactNode][] = [
    ['Student', person ? `${person.displayName} · ${person.email}` : 'Student'],
    ['Plan', `${p.planName} (${monthsLabel(p.durationMonths)})`],
    [
      'Paid',
      <>
        {formatPrice(p.amountMinor, p.currency)}
        {p.listPriceMinor !== null && p.listPriceMinor !== p.amountMinor && (
          <span className="text-muted">
            {' '}
            · list price {formatPrice(p.listPriceMinor, p.currency)}
            {p.discountCode && `, code ${p.discountCode}`}
          </span>
        )}
      </>,
    ],
    ['Refunded', p.refundedMinor > 0 ? formatPrice(p.refundedMinor, p.currency) : 'Nothing'],
    [
      'Plan period',
      p.periodStartsAt && p.periodEndsAt
        ? `${day.format(new Date(p.periodStartsAt))} – ${day.format(new Date(p.periodEndsAt))}${
            p.periodStatus === 'CANCELLED' ? ' (stopped)' : p.periodStatus === 'EXPIRED' ? ' (ended)' : ''
          }`
        : 'None',
    ],
    [
      'Paid through',
      p.provider === 'PAYHERE' ? 'PayHere' : p.provider === 'FREE' ? 'Free with a code' : 'Test payment (no money moved)',
    ],
    ['PayHere payment ID', p.provider === 'PAYHERE' && p.providerRef ? <span className="font-mono">{p.providerRef}</span> : '—'],
    ['Payment ID', <span className="font-mono text-[13px] break-all">{p.id}</span>],
  ];
  return (
    <div className="flex flex-col gap-3">
      <Badge tone={stateTone(p)}>{paymentStateLabel(p)}</Badge>
      <dl className="grid gap-x-6 gap-y-2 text-[14.5px] sm:grid-cols-[160px_1fr]">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-muted">{k}</dt>
            <dd className="min-w-0">{v}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

function Refunds({ refunds }: { refunds: Refund[] }) {
  if (refunds.length === 0) return null;
  return (
    <section className="flex flex-col gap-2" aria-labelledby="refunds-heading">
      <h3 id="refunds-heading" className="font-bold">
        Refunds
      </h3>
      <ul className="flex flex-col gap-2">
        {refunds.map((r) => (
          <RefundRow key={r.id} refund={r} />
        ))}
      </ul>
    </section>
  );
}

function RefundRow({ refund: r }: { refund: Refund }) {
  const queryClient = useQueryClient();
  const [reference, setReference] = useState('');
  const resolve = useMutation({
    mutationFn: (outcome: 'SUCCEEDED' | 'FAILED') => resolveRefund(r.id, outcome, reference.trim() || null, null),
    onSuccess: () => invalidate(queryClient),
  });
  const tone = r.status === 'SUCCEEDED' ? 'free' : r.status === 'PENDING' ? 'warning' : 'danger';
  const label = r.status === 'SUCCEEDED' ? 'Refunded' : r.status === 'PENDING' ? 'Waiting for PayHere' : 'Not refunded';
  return (
    <li className="flex flex-col gap-2 rounded-md border border-border p-3 text-[14px]">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold tabular-nums">{formatPrice(r.amountMinor, r.currency)}</span>
        <Badge tone={tone}>{label}</Badge>
      </div>
      <p className="text-ink-2">{r.reason}</p>
      <p className="text-[13px] text-muted">
        {dateTime.format(new Date(r.createdAt))} · {r.method === 'PAYHERE_API' ? 'Through PayHere' : 'Recorded by hand'}
        {r.providerRef && ` · ref ${r.providerRef}`}
        {r.stopAccess ? ' · plan stopped' : ' · plan kept'}
      </p>
      {r.failureReason && <p className="text-[13px] text-danger-ink">{r.failureReason}</p>}
      {r.status === 'PENDING' && (
        <div className="flex flex-col gap-2 border-t border-border pt-2">
          <p className="text-[13px] text-ink-2">
            PayHere didn’t answer. Check the payment in PayHere’s portal, then record what happened.
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <div className="min-w-48 flex-1">
              <TextField
                label="PayHere refund number (optional)"
                value={reference}
                onChange={(e) => setReference(e.target.value)}
              />
            </div>
            <Button size="sm" disabled={resolve.isPending} onClick={() => resolve.mutate('SUCCEEDED')}>
              It was refunded
            </Button>
            <Button size="sm" variant="secondary" disabled={resolve.isPending} onClick={() => resolve.mutate('FAILED')}>
              It wasn’t
            </Button>
          </div>
          {resolve.isError && <p className="text-[13px] text-danger-ink">{errorText(resolve.error)}</p>}
        </div>
      )}
    </li>
  );
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>) {
  void queryClient.invalidateQueries({ queryKey: financeKeys.all });
  void queryClient.invalidateQueries({ queryKey: billingAdminKeys.all });
  void queryClient.invalidateQueries({ queryKey: accessKeys.all });
}

function RefundForm({ detail }: { detail: PaymentDetail }) {
  const p = detail.payment;
  const queryClient = useQueryClient();
  const full = (detail.refundableMinor / 100).toFixed(2);
  const [amount, setAmount] = useState(full);
  const [reason, setReason] = useState('');
  const [stopAccess, setStopAccess] = useState(true);
  const [method, setMethod] = useState<'PAYHERE_API' | 'MANUAL'>(detail.apiRefunds ? 'PAYHERE_API' : 'MANUAL');
  const [reference, setReference] = useState('');
  const [errors, setErrors] = useState<{ amount?: string; reason?: string }>({});
  const [confirming, setConfirming] = useState<RefundInput | null>(null);
  const refund = useMutation({
    mutationFn: (input: RefundInput) => refundPayment(p.id, input),
    onSuccess: () => {
      setConfirming(null);
      setReason('');
      setReference('');
      invalidate(queryClient);
    },
  });
  const minor = parsePrice(amount);

  const review = () => {
    const next: typeof errors = {};
    if (minor === null || minor <= 0) next.amount = 'Enter an amount such as 1500 or 1500.00.';
    else if (minor > detail.refundableMinor)
      next.amount = `At most ${formatPrice(detail.refundableMinor, p.currency)} can be refunded.`;
    if (reason.trim().length < 3) next.reason = 'Say why, in a few words. Students don’t see this.';
    setErrors(next);
    if (Object.keys(next).length > 0 || minor === null) return;
    setConfirming({
      amountMinor: minor === detail.refundableMinor ? null : minor,
      reason: reason.trim(),
      stopAccess,
      method,
      reference: method === 'MANUAL' && reference.trim() ? reference.trim() : null,
    });
  };

  const shown = minor ?? 0;
  const last = refund.data;
  return (
    <section className="flex flex-col gap-4 rounded-md border border-border p-4" aria-labelledby="refund-heading">
      <div>
        <h3 id="refund-heading" className="font-bold">
          Refund
        </h3>
        <p className="text-[13.5px] text-muted">Up to {formatPrice(detail.refundableMinor, p.currency)} can be refunded.</p>
      </div>
      {last && (
        <Alert
          tone={last.status === 'SUCCEEDED' ? 'success' : last.status === 'PENDING' ? 'warning' : 'error'}
          title={
            last.status === 'SUCCEEDED'
              ? 'Refunded'
              : last.status === 'PENDING'
                ? 'Waiting for PayHere'
                : 'PayHere didn’t refund it'
          }
        >
          {last.status === 'FAILED'
            ? last.failureReason
            : `${formatPrice(last.amountMinor, last.currency)}. ${last.stopAccess ? 'The plan has stopped.' : 'The plan continues.'}`}
        </Alert>
      )}
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label={`Amount (${p.currency})`}
          inputMode="decimal"
          value={amount}
          onChange={(e) => {
            setAmount(e.target.value);
            setConfirming(null);
          }}
          help="The full amount left is filled in; lower it for a partial refund."
          error={errors.amount}
        />
        <div className="flex flex-col justify-end">
          <CheckboxField
            label="Stop the plan now"
            help="The student loses this plan’s access straight away. Their results are kept."
            checked={stopAccess}
            onChange={(e) => {
              setStopAccess(e.target.checked);
              setConfirming(null);
            }}
          />
        </div>
      </div>
      <TextAreaField
        label="Reason"
        rows={2}
        maxLength={500}
        value={reason}
        onChange={(e) => {
          setReason(e.target.value);
          setConfirming(null);
        }}
        help="Kept in the payment’s history. Students don’t see it."
        error={errors.reason}
      />
      {detail.apiRefunds ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-semibold">How</legend>
          {(
            [
              ['PAYHERE_API', 'Refund through PayHere now', 'The money goes back to the card or account the student paid with.'],
              ['MANUAL', 'I already refunded it in PayHere’s portal', 'Only records the refund here.'],
            ] as const
          ).map(([value, label, help]) => (
            <label key={value} className="flex items-start gap-2 text-[15px]">
              <input
                type="radio"
                name="refund-method"
                className="mt-1 size-4 accent-[var(--color-primary)]"
                checked={method === value}
                onChange={() => {
                  setMethod(value);
                  setConfirming(null);
                }}
              />
              <span>
                {label}
                <span className="block text-[13px] text-muted">{help}</span>
              </span>
            </label>
          ))}
        </fieldset>
      ) : (
        <Alert tone="info" title="Refund in PayHere first">
          {p.provider === 'TEST'
            ? 'This was a test payment, so no money moved. Recording a refund here only updates the records.'
            : 'PayHere refunds aren’t set up here yet. Refund the payment in PayHere’s portal, then record it below.'}
        </Alert>
      )}
      {method === 'MANUAL' && (
        <TextField
          label="PayHere refund number or note (optional)"
          maxLength={100}
          value={reference}
          onChange={(e) => setReference(e.target.value)}
        />
      )}
      {refund.isError && (
        <Alert tone="error" title="Couldn’t refund">
          {errorText(refund.error)}
        </Alert>
      )}
      {confirming ? (
        <div className="flex flex-col gap-3 rounded-md bg-warning-soft p-3">
          <p className="text-[14.5px] text-ink">
            {method === 'PAYHERE_API'
              ? `PayHere will send ${formatPrice(shown, p.currency)} back to the student. This can’t be undone.`
              : `This records a refund of ${formatPrice(shown, p.currency)} made outside this site.`}{' '}
            {stopAccess ? 'The plan stops now.' : 'The plan continues.'}
          </p>
          <div className="flex flex-wrap gap-2">
            <Button variant="danger" disabled={refund.isPending} onClick={() => refund.mutate(confirming)}>
              {refund.isPending
                ? 'Refunding…'
                : method === 'PAYHERE_API'
                  ? `Refund ${formatPrice(shown, p.currency)}`
                  : 'Record the refund'}
            </Button>
            <Button variant="ghost" disabled={refund.isPending} onClick={() => setConfirming(null)}>
              Back
            </Button>
          </div>
        </div>
      ) : (
        <div>
          <Button variant="secondary" onClick={review}>
            Review refund
          </Button>
        </div>
      )}
    </section>
  );
}
