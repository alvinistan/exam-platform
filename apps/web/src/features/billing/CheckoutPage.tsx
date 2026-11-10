import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { ArrowLeft, Lock } from 'lucide-react';
import { Alert, Button, Card, EmptyState, ErrorState, Skeleton, TextField } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthContext';
import { TestModeNotice } from './PlansPage';
import {
  describeLimits,
  describeScope,
  formatPrice,
  monthsLabel,
  priceIn,
  startCheckout,
  submitToProvider,
  usePlans,
  type Checkout,
  type Currency,
  type Customer,
} from './api';

const required = (max: number) => z.string().trim().min(1, 'Required.').max(max, `Use ${max} characters or fewer.`);

const schema = z.object({
  firstName: required(50),
  lastName: required(50),
  email: z.email('Enter an email address.').max(100),
  phone: z
    .string()
    .trim()
    .regex(/^\+?[0-9 ()-]{7,20}$/, 'Enter a phone number, for example +94 77 123 4567.'),
  address: required(100),
  city: required(50),
  country: required(50),
});

/**
 * /checkout/:planId — the buyer details PayHere asks for, then off to PayHere (or the labelled test page). Premium
 * starts only when PayHere confirms the payment to our server, never from this page.
 */
export function CheckoutPage() {
  const { planId = '' } = useParams();
  const [params] = useSearchParams();
  const asked: Currency = params.get('currency') === 'USD' ? 'USD' : 'LKR';
  const plans = usePlans();
  const found = plans.data?.plans.find((p) => p.id === planId);
  // Use the currency asked for when the plan is sold in it; otherwise the one it is sold in.
  const currency: Currency = found && priceIn(found, asked) === null ? (asked === 'LKR' ? 'USD' : 'LKR') : asked;
  const { state } = useAuth();
  const navigate = useNavigate();
  // One key for this visit: pressing Pay twice, or retrying after a network error, never creates two payments.
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  const user = state.status === 'signedIn' ? state.user : null;
  const [first = '', ...rest] = (user?.displayName ?? '').trim().split(/\s+/);
  const form = useForm<Customer>({
    resolver: zodResolver(schema),
    defaultValues: {
      firstName: first,
      lastName: rest.join(' '),
      email: user?.email ?? '',
      phone: '',
      address: '',
      city: '',
      country: 'Sri Lanka',
    },
  });

  const go = useMutation({
    mutationFn: (customer: Customer) => startCheckout(planId, currency, idempotencyKey, customer),
    onSuccess: (checkout: Checkout) => {
      if (checkout.provider === 'TEST') {
        void navigate(`/checkout/test/${checkout.transactionId}`);
      } else {
        submitToProvider(checkout);
      }
    },
  });

  if (plans.isPending) {
    return (
      <Shell>
        <Skeleton className="h-96 w-full" />
      </Shell>
    );
  }
  if (plans.isError) {
    return (
      <Shell>
        <ErrorState title="We couldn’t load this plan" onRetry={() => void plans.refetch()} />
      </Shell>
    );
  }
  const plan = plans.data.plans.find((p) => p.id === planId);
  if (!plan || (plan.priceLkrMinor === null && plan.priceUsdMinor === null)) {
    return (
      <Shell>
        <EmptyState
          title="This plan isn’t on sale"
          action={
            <Link to="/plans" className="font-semibold">
              See the plans on sale
            </Link>
          }
        >
          <p>It may have been changed or withdrawn.</p>
        </EmptyState>
      </Shell>
    );
  }

  const errors = form.formState.errors;
  const price = priceIn(plan, currency) ?? 0;
  const submitting = go.isPending || go.isSuccess;
  return (
    <Shell>
      {plans.data.testPayments && <TestModeNotice />}
      <div className="grid gap-6 md:grid-cols-[1fr_320px] md:items-start">
        <Card className="p-6 sm:p-7">
          <form noValidate onSubmit={form.handleSubmit((values) => go.mutate(values))} className="flex flex-col gap-5">
            <div>
              <h2 className="text-lg font-bold">Your details</h2>
              <p className="text-[14.5px] text-muted">PayHere needs these for the payment and sends the receipt to your email.</p>
            </div>
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="First name"
                autoComplete="given-name"
                error={errors.firstName?.message}
                {...form.register('firstName')}
              />
              <TextField
                label="Last name"
                autoComplete="family-name"
                error={errors.lastName?.message}
                {...form.register('lastName')}
              />
              <TextField
                label="Email"
                type="email"
                autoComplete="email"
                error={errors.email?.message}
                {...form.register('email')}
              />
              <TextField label="Phone" type="tel" autoComplete="tel" error={errors.phone?.message} {...form.register('phone')} />
            </div>
            <TextField
              label="Address"
              autoComplete="street-address"
              error={errors.address?.message}
              {...form.register('address')}
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField label="City" autoComplete="address-level2" error={errors.city?.message} {...form.register('city')} />
              <TextField
                label="Country"
                autoComplete="country-name"
                error={errors.country?.message}
                {...form.register('country')}
              />
            </div>
            {go.isError && (
              <Alert tone="error" title="We couldn’t start the payment">
                {go.error instanceof ApiError ? go.error.message : 'Please try again.'}
              </Alert>
            )}
            <Button type="submit" disabled={submitting} className="self-start">
              <Lock aria-hidden="true" className="size-4" />
              {submitting
                ? 'Opening the payment page…'
                : plans.data.testPayments
                  ? 'Continue to the test payment'
                  : `Pay ${formatPrice(price, currency)} with PayHere`}
            </Button>
          </form>
        </Card>
        <Card className="flex flex-col gap-3 p-6">
          <h2 className="text-[13px] font-bold tracking-wider text-muted uppercase">Order</h2>
          <p className="text-lg font-bold">{plan.name}</p>
          <p className="text-ink-2">
            {describeScope(plan)} · {monthsLabel(plan.durationMonths)}
          </p>
          <ul className="flex flex-col gap-1 text-[14px] text-ink-2">
            {describeLimits(plan.limits).map((line) => (
              <li key={line}>{plan.scopeType === 'BUNDLE' ? `${line} per subject` : line}</li>
            ))}
          </ul>
          <p className="flex items-baseline justify-between border-t border-border pt-3">
            <span className="text-muted">Total</span>
            <span className="text-xl font-extrabold tabular-nums">{formatPrice(price, currency)}</span>
          </p>
          <p className="text-[13px] text-muted">
            One payment, no automatic renewal. The plan starts as soon as the payment is confirmed and adds to any plan you
            already have.
          </p>
        </Card>
      </div>
    </Shell>
  );
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto flex max-w-[1000px] flex-col gap-6 px-4 py-8 sm:px-8 sm:py-10">
      <title>Checkout · Leenex Labs</title>
      <Link to="/plans" className="inline-flex items-center gap-1.5 self-start font-semibold">
        <ArrowLeft aria-hidden="true" className="size-4" />
        All plans
      </Link>
      <h1 className="text-3xl font-extrabold tracking-tight">Get Premium</h1>
      {children}
    </div>
  );
}
