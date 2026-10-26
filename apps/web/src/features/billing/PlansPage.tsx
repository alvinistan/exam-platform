import { Link } from 'react-router';
import { Check, CreditCard } from 'lucide-react';
import { Alert, Badge, Card, EmptyState, ErrorState, Skeleton, buttonClasses } from '@/components/ui';
import { useAuth } from '@/lib/auth/AuthContext';
import { cn } from '@/lib/cn';
import { formatPrice, formatPriceShort, monthsLabel, perMonth, useMySubscription, usePlans, type PublicPlan } from './api';

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' });

const benefits = [
  'Unlimited Practice, Mock and Adaptive exams on every exam',
  'Questions chosen at your level on each topic',
  'Your progress and results are kept',
];

/** /plans — Premium plans on sale, priced by the administrator. Public; choosing one asks the visitor to sign in. */
export function PlansPage() {
  const plans = usePlans();
  const { state } = useAuth();
  const mine = useMySubscription();

  return (
    <div className="mx-auto flex max-w-[1100px] flex-col gap-8 px-4 py-8 sm:px-8 sm:py-12">
      <title>Premium plans · Leenex Labs</title>
      <header className="flex max-w-2xl flex-col gap-2">
        <Badge tone="premium" className="self-start">
          Premium
        </Badge>
        <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">Keep practising without limits</h1>
        <p className="text-lg text-ink-2">
          Every exam gives you a few free attempts of each exam type. Premium removes the limit on every exam for as long as your
          plan lasts.
        </p>
      </header>

      <ul className="flex flex-col gap-2 sm:flex-row sm:gap-6">
        {benefits.map((b) => (
          <li key={b} className="flex items-start gap-2 text-[15px] text-ink-2">
            <Check aria-hidden="true" className="mt-0.5 size-4.5 flex-none text-success" />
            {b}
          </li>
        ))}
      </ul>

      {state.status === 'signedIn' && mine.data?.premium && mine.data.premiumEndsAt && (
        <Alert tone="success" title="You have Premium">
          Until {longDate.format(new Date(mine.data.premiumEndsAt))}. A new plan starts when this one ends, so no time is lost.
        </Alert>
      )}

      {plans.data?.testPayments && <TestModeNotice />}

      {plans.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-64" />
          ))}
        </div>
      ) : plans.isError ? (
        <ErrorState title="We couldn’t load the plans" onRetry={() => void plans.refetch()} />
      ) : plans.data.plans.length === 0 ? (
        <EmptyState icon={CreditCard} title="No plans on sale right now">
          <p>Please check back soon. Your free attempts are still available on every exam.</p>
        </EmptyState>
      ) : (
        <PlanGrid plans={plans.data.plans} />
      )}

      <p className="text-[13.5px] text-muted">
        Payments are processed by PayHere. You pay once for the period you choose; nothing renews automatically.
      </p>
    </div>
  );
}

export function TestModeNotice() {
  return (
    <Alert tone="warning" title="Test payments">
      Real payments aren’t switched on yet. Choosing a plan opens a test page where no money moves.
    </Alert>
  );
}

function PlanGrid({ plans }: { plans: PublicPlan[] }) {
  // "Best value": the lowest price per month, when plans share a currency and there is a real difference.
  const sameCurrency = plans.every((p) => p.currency === plans[0]?.currency);
  const cheapest = sameCurrency && plans.length > 1 ? Math.min(...plans.map(perMonth)) : null;
  const priciest = sameCurrency ? Math.max(...plans.map(perMonth)) : null;
  return (
    <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {plans.map((p) => {
        const best = cheapest !== null && perMonth(p) === cheapest && cheapest < (priciest ?? 0);
        const saving = best && priciest ? Math.round((1 - cheapest / priciest) * 100) : 0;
        return (
          <li key={p.id}>
            <Card className={cn('flex h-full flex-col gap-4 p-6', best && 'border-primary ring-2 ring-primary/20')}>
              <div className="flex items-start justify-between gap-2">
                <h2 className="text-lg font-bold">{p.name}</h2>
                {best && <Badge tone="primary">Best value</Badge>}
              </div>
              <div>
                <p className="text-3xl font-extrabold tracking-tight tabular-nums">
                  {formatPriceShort(p.priceMinor, p.currency)}
                </p>
                <p className="text-[14px] text-muted">
                  for {monthsLabel(p.durationMonths)}
                  {p.durationMonths > 1 && <> · {formatPrice(perMonth(p), p.currency)} a month</>}
                </p>
                {saving > 0 && <p className="mt-1 text-[13.5px] font-semibold text-success-ink">Save {saving}% a month</p>}
              </div>
              {p.description && <p className="text-[14.5px] text-ink-2">{p.description}</p>}
              <Link
                to={`/checkout/${p.id}`}
                className={cn(buttonClasses(best ? 'primary' : 'secondary'), 'mt-auto w-full')}
                aria-label={`Choose ${p.name}, ${formatPrice(p.priceMinor, p.currency)}`}
              >
                Choose
              </Link>
            </Card>
          </li>
        );
      })}
    </ul>
  );
}
