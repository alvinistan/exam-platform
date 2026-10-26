import { useEffect, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { CircleCheck, CircleX, Clock } from 'lucide-react';
import { Card, ErrorState, Skeleton, buttonClasses } from '@/components/ui';
import { accessKeys } from '@/features/access/api';
import { billingKeys, formatPrice, usePayment, type Payment } from './api';

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' });
/** How long the page keeps asking before suggesting the student look again later. */
const PATIENCE_MS = 90_000;

/**
 * /checkout/return?tx= — where PayHere sends the student back. PayHere doesn't say here whether the payment worked,
 * so the page asks our server, which knows only from PayHere's own signed message.
 */
export function CheckoutReturnPage() {
  const [params] = useSearchParams();
  const tx = params.get('tx');
  const payment = usePayment(tx, 2000);
  const queryClient = useQueryClient();
  const [waitedTooLong, setWaitedTooLong] = useState(false);

  const status = payment.data?.status;
  useEffect(() => {
    if (status === 'SUCCEEDED') {
      // Premium changes what every exam page shows.
      void queryClient.invalidateQueries({ queryKey: accessKeys.all });
      void queryClient.invalidateQueries({ queryKey: billingKeys.all });
    }
  }, [status, queryClient]);
  useEffect(() => {
    if (status !== 'PENDING') return;
    const t = window.setTimeout(() => setWaitedTooLong(true), PATIENCE_MS);
    return () => window.clearTimeout(t);
  }, [status]);

  return (
    <Frame>
      {!tx ? (
        <ErrorState title="We don’t know which payment this is" />
      ) : payment.isPending ? (
        <Skeleton className="h-56 w-full" />
      ) : payment.isError ? (
        <ErrorState title="We couldn’t check your payment" onRetry={() => void payment.refetch()} />
      ) : (
        <Result payment={payment.data} waitedTooLong={waitedTooLong} />
      )}
    </Frame>
  );
}

/** /checkout/cancel?tx= — the student left PayHere without paying. */
export function CheckoutCancelPage() {
  return (
    <Frame>
      <Card className="flex flex-col items-center gap-4 p-8 text-center">
        <CircleX aria-hidden="true" className="size-12 text-muted" />
        <h1 className="text-2xl font-extrabold tracking-tight">Payment cancelled</h1>
        <p className="text-ink-2">Nothing was charged. You can choose a plan again whenever you like.</p>
        <Actions
          primary={{ to: '/plans', label: 'See the plans' }}
          secondary={{ to: '/dashboard', label: 'Go to your dashboard' }}
        />
      </Card>
    </Frame>
  );
}

function Result({ payment, waitedTooLong }: { payment: Payment; waitedTooLong: boolean }) {
  const [openedAt] = useState(() => Date.now());
  const amount = formatPrice(payment.amountMinor, payment.currency);
  if (payment.status === 'SUCCEEDED') {
    return (
      <Card className="flex flex-col items-center gap-4 p-8 text-center">
        <CircleCheck aria-hidden="true" className="size-12 text-success" />
        <h1 className="text-2xl font-extrabold tracking-tight">You have Premium</h1>
        <p role="status" className="text-ink-2">
          Payment of {amount} received for {payment.planName}.
          {payment.premiumStartsAt && payment.premiumEndsAt && (
            <>
              {' '}
              {Date.parse(payment.premiumStartsAt) > openedAt + 60_000
                ? `It starts on ${longDate.format(new Date(payment.premiumStartsAt))}, when your current Premium ends, and runs until`
                : 'Every exam is unlimited until'}{' '}
              {longDate.format(new Date(payment.premiumEndsAt))}.
            </>
          )}
        </p>
        <Actions
          primary={{ to: '/dashboard', label: 'Go to your dashboard' }}
          secondary={{ to: '/exams', label: 'Browse exams' }}
        />
      </Card>
    );
  }
  if (payment.status === 'PENDING') {
    return (
      <Card className="flex flex-col items-center gap-4 p-8 text-center">
        <Clock aria-hidden="true" className="size-12 text-warning" />
        <h1 className="text-2xl font-extrabold tracking-tight">Confirming your payment…</h1>
        <p role="status" className="text-ink-2">
          {waitedTooLong
            ? 'PayHere hasn’t confirmed it yet. This can take a few minutes; Premium switches on by itself once it does, and your account page shows the status.'
            : 'Waiting for PayHere to confirm. This usually takes a few seconds.'}
        </p>
        {waitedTooLong && <Actions primary={{ to: '/account', label: 'Go to your account' }} />}
      </Card>
    );
  }
  return (
    <Card className="flex flex-col items-center gap-4 p-8 text-center">
      <CircleX aria-hidden="true" className="size-12 text-danger" />
      <h1 className="text-2xl font-extrabold tracking-tight">
        {payment.status === 'CANCELLED' ? 'Payment cancelled' : 'The payment didn’t go through'}
      </h1>
      <p role="status" className="text-ink-2">
        {payment.failureReason === 'CHARGEDBACK'
          ? 'This payment was reversed, so its Premium has ended.'
          : 'Nothing was charged. You can try again, perhaps with another card.'}
      </p>
      <Actions primary={{ to: '/plans', label: 'Try again' }} secondary={{ to: '/dashboard', label: 'Go to your dashboard' }} />
    </Card>
  );
}

function Actions({ primary, secondary }: { primary: { to: string; label: string }; secondary?: { to: string; label: string } }) {
  return (
    <div className="flex flex-wrap justify-center gap-3">
      <Link to={primary.to} className={buttonClasses('primary')}>
        {primary.label}
      </Link>
      {secondary && (
        <Link to={secondary.to} className={buttonClasses('secondary')}>
          {secondary.label}
        </Link>
      )}
    </div>
  );
}

function Frame({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[560px] flex-col gap-6 px-4 py-12 sm:px-8">
      <title>Payment · Leenex Labs</title>
      {children}
    </div>
  );
}
