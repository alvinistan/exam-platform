import { useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { ArrowLeft, Gift, Lock, Tag } from 'lucide-react';
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
  priceNow,
  quotePrice,
  startCheckout,
  submitToProvider,
  usePlans,
  type Checkout,
  type Currency,
  type Customer,
  type PublicPlan,
  type Quote,
} from './api';

const shortDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

/** The message for one field of a 400, or the error's own message. */
function messageFor(error: unknown, field: string): string {
  if (error instanceof ApiError) {
    return error.problem?.errors?.find((e) => e.field === field)?.message ?? error.message;
  }
  return 'Please try again.';
}

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
  // One key per order: pressing Pay twice, or retrying after a network error, never creates two payments. A new
  // code makes a new order, so the key changes with it.
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID());
  const [applied, setApplied] = useState<Quote | null>(null);

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
    mutationFn: (customer: Customer | null) => startCheckout(planId, currency, applied?.code ?? null, idempotencyKey, customer),
    onSuccess: (checkout: Checkout) => {
      if (checkout.provider === 'FREE') {
        void navigate(`/checkout/return?tx=${checkout.transactionId}`);
      } else if (checkout.provider === 'TEST') {
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
  const total = applied ? applied.totalMinor : (priceNow(plan, currency) ?? 0);
  const free = applied?.totalMinor === 0;
  const submitting = go.isPending || go.isSuccess;
  const changeCode = (quote: Quote | null) => {
    setApplied(quote);
    setIdempotencyKey(crypto.randomUUID());
    go.reset();
  };
  const startError = go.isError && (
    <Alert tone="error" title="We couldn’t start the payment">
      {messageFor(go.error, 'code')}
    </Alert>
  );
  return (
    <Shell>
      {plans.data.testPayments && <TestModeNotice />}
      <div className="grid gap-6 md:grid-cols-[1fr_320px] md:items-start">
        {free ? (
          <Card className="flex flex-col items-start gap-4 p-6 sm:p-7">
            <Gift aria-hidden="true" className="size-8 text-success" />
            <div>
              <h2 className="text-lg font-bold">Nothing to pay</h2>
              <p className="text-[14.5px] text-muted">
                Your code {applied.code} covers the whole price, so there are no payment details to fill in.
              </p>
            </div>
            {startError}
            <Button disabled={submitting} onClick={() => go.mutate(null)}>
              {submitting ? 'Starting your plan…' : 'Start my plan'}
            </Button>
          </Card>
        ) : (
          <Card className="p-6 sm:p-7">
            <form noValidate onSubmit={form.handleSubmit((values) => go.mutate(values))} className="flex flex-col gap-5">
              <div>
                <h2 className="text-lg font-bold">Your details</h2>
                <p className="text-[14.5px] text-muted">
                  PayHere needs these for the payment and sends the receipt to your email.
                </p>
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
                <TextField
                  label="Phone"
                  type="tel"
                  autoComplete="tel"
                  error={errors.phone?.message}
                  {...form.register('phone')}
                />
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
              {startError}
              <Button type="submit" disabled={submitting} className="self-start">
                <Lock aria-hidden="true" className="size-4" />
                {submitting
                  ? 'Opening the payment page…'
                  : plans.data.testPayments
                    ? 'Continue to the test payment'
                    : `Pay ${formatPrice(total, currency)} with PayHere`}
              </Button>
            </form>
          </Card>
        )}
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
          <PriceLines plan={plan} currency={currency} applied={applied} total={total} />
          <CodeBox planId={plan.id} currency={currency} applied={applied} disabled={submitting} onChange={changeCode} />
          <p className="text-[13px] text-muted">
            One payment, no automatic renewal. The plan starts as soon as the payment is confirmed and adds to any plan you
            already have.
          </p>
        </Card>
      </div>
    </Shell>
  );
}

/** Regular price, the sale price while on, the code's discount, and the total. */
function PriceLines({
  plan,
  currency,
  applied,
  total,
}: {
  plan: PublicPlan;
  currency: Currency;
  applied: Quote | null;
  total: number;
}) {
  const regular = priceIn(plan, currency) ?? 0;
  const now = priceNow(plan, currency) ?? regular;
  return (
    <dl className="flex flex-col gap-1.5 border-t border-border pt-3 text-[14.5px]">
      {now < regular && (
        <>
          <div className="flex justify-between gap-3">
            <dt className="text-muted">Regular price</dt>
            <dd className="text-muted tabular-nums line-through">{formatPrice(regular, currency)}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-ink-2">
              Sale price
              {plan.saleEndsAt && <span className="text-muted"> · until {shortDate.format(new Date(plan.saleEndsAt))}</span>}
            </dt>
            <dd className="tabular-nums">{formatPrice(now, currency)}</dd>
          </div>
        </>
      )}
      {applied && applied.discountMinor > 0 && (
        <div className="flex justify-between gap-3">
          <dt className="text-success-ink">Code {applied.code}</dt>
          <dd className="text-success-ink tabular-nums">−{formatPrice(applied.discountMinor, currency)}</dd>
        </div>
      )}
      <div className="flex items-baseline justify-between gap-3 pt-1">
        <dt className="text-muted">Total</dt>
        <dd className="text-xl font-extrabold tabular-nums">{total === 0 ? 'Free' : formatPrice(total, currency)}</dd>
      </div>
    </dl>
  );
}

/** A discount code: checked by the server, which works the price out again at checkout. */
function CodeBox({
  planId,
  currency,
  applied,
  disabled,
  onChange,
}: {
  planId: string;
  currency: Currency;
  applied: Quote | null;
  disabled: boolean;
  onChange: (quote: Quote | null) => void;
}) {
  const [text, setText] = useState('');
  const check = useMutation({
    mutationFn: (code: string) => quotePrice(planId, currency, code),
    onSuccess: (quote) => {
      onChange(quote);
      setText('');
    },
  });
  if (applied?.code) {
    return (
      <p className="flex items-center justify-between gap-3 rounded-md bg-success-soft px-3 py-2 text-[14px]">
        <span className="inline-flex items-center gap-1.5 font-semibold text-success-ink">
          <Tag aria-hidden="true" className="size-4" />
          {applied.code} applied
        </span>
        <Button variant="ghost" size="sm" disabled={disabled} onClick={() => onChange(null)}>
          Remove
        </Button>
      </p>
    );
  }
  return (
    <form
      noValidate
      className="flex items-start gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        if (text.trim()) check.mutate(text.trim());
      }}
    >
      <div className="min-w-0 flex-1">
        <TextField
          label="Discount code"
          hideLabel
          placeholder="Discount code"
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          maxLength={40}
          value={text}
          disabled={disabled}
          onChange={(e) => {
            setText(e.target.value);
            check.reset();
          }}
          error={check.isError ? messageFor(check.error, 'code') : undefined}
        />
      </div>
      <Button type="submit" variant="secondary" disabled={disabled || !text.trim() || check.isPending}>
        {check.isPending ? 'Checking…' : 'Apply'}
      </Button>
    </form>
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
