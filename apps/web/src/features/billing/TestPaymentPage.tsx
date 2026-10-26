import { Link, useNavigate, useParams } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { FlaskConical } from 'lucide-react';
import { Alert, Button, Card, ErrorState, Skeleton } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { completeTestPayment, formatPrice, usePayment } from './api';

/**
 * /checkout/test/:transactionId — the labelled TEST provider. Stands in for PayHere while real payments aren’t
 * configured; the server refuses it when they are. No money moves.
 */
export function TestPaymentPage() {
  const { transactionId = '' } = useParams();
  const payment = usePayment(transactionId);
  const navigate = useNavigate();
  const finish = useMutation({
    mutationFn: (outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED') => completeTestPayment(transactionId, outcome),
    onSuccess: (_result, outcome) =>
      void navigate(outcome === 'CANCELLED' ? `/checkout/cancel?tx=${transactionId}` : `/checkout/return?tx=${transactionId}`, {
        replace: true,
      }),
  });

  return (
    <div className="mx-auto flex max-w-[560px] flex-col gap-6 px-4 py-10 sm:px-8">
      <title>Test payment · Leenex Labs</title>
      <div className="flex items-center gap-3">
        <span className="flex size-11 flex-none items-center justify-center rounded-full bg-warning-soft text-warning-ink">
          <FlaskConical aria-hidden="true" className="size-5" />
        </span>
        <h1 className="text-2xl font-extrabold tracking-tight">Test payment</h1>
      </div>
      <Alert tone="warning" title="No money moves on this page">
        Real payments aren’t switched on yet. Choose what should happen, as if PayHere had answered.
      </Alert>
      {payment.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : payment.isError ? (
        <ErrorState title="We couldn’t find this payment" onRetry={() => void payment.refetch()} />
      ) : payment.data.provider !== 'TEST' || payment.data.status !== 'PENDING' ? (
        <Card className="flex flex-col gap-3 p-6">
          <p>This payment is already finished.</p>
          <Link to={`/checkout/return?tx=${transactionId}`} className="font-semibold">
            See the result
          </Link>
        </Card>
      ) : (
        <Card className="flex flex-col gap-5 p-6">
          <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-[15px]">
            <dt className="text-muted">Plan</dt>
            <dd className="font-semibold">{payment.data.planName}</dd>
            <dt className="text-muted">Amount</dt>
            <dd className="font-semibold tabular-nums">{formatPrice(payment.data.amountMinor, payment.data.currency)}</dd>
          </dl>
          {finish.isError && (
            <Alert tone="error" title="That didn’t work">
              {finish.error instanceof ApiError ? finish.error.message : 'Please try again.'}
            </Alert>
          )}
          <div className="flex flex-wrap gap-3">
            <Button onClick={() => finish.mutate('SUCCEEDED')} disabled={finish.isPending}>
              Pay (test)
            </Button>
            <Button variant="secondary" onClick={() => finish.mutate('FAILED')} disabled={finish.isPending}>
              Simulate a declined card
            </Button>
            <Button variant="ghost" onClick={() => finish.mutate('CANCELLED')} disabled={finish.isPending}>
              Cancel
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
}
