import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';

export type Currency = 'LKR' | 'USD';
export type ScopeType = 'EXAM' | 'BUNDLE' | 'ALL';

/** Attempts per exam type, per exam, for the length of a plan: null = unlimited, 0 = not included. */
export interface Limits {
  practice: number | null;
  mock: number | null;
  adaptive: number | null;
}

/** contracts/openapi/subscription-service.yaml → PublicPlan. Prices, scope and limits are set by administrators. */
export interface PublicPlan {
  id: string;
  name: string;
  description: string;
  scopeType: ScopeType;
  categoryId: string | null;
  /** Published exams covered; empty for ALL (every exam). */
  exams: { examId: string; title: string; slug: string }[];
  durationMonths: 1 | 3 | 6 | 12;
  /** Smallest currency unit: 450000 = LKR 4,500.00. Null = not sold in that currency. */
  priceLkrMinor: number | null;
  priceUsdMinor: number | null;
  limits: Limits;
  /** The price while a sale is on now; null when there is no sale in that currency. */
  salePriceLkrMinor: number | null;
  salePriceUsdMinor: number | null;
  saleEndsAt: string | null;
}

export const priceIn = (p: Pick<PublicPlan, 'priceLkrMinor' | 'priceUsdMinor'>, currency: Currency) =>
  currency === 'USD' ? p.priceUsdMinor : p.priceLkrMinor;

/** The sale price in a currency while a sale is on, else null. */
export const saleIn = (p: Pick<PublicPlan, 'salePriceLkrMinor' | 'salePriceUsdMinor'>, currency: Currency) =>
  currency === 'USD' ? p.salePriceUsdMinor : p.salePriceLkrMinor;

/** What the plan costs today in a currency (the sale price while on); null when not sold in it. */
export const priceNow = (p: PublicPlan, currency: Currency) => saleIn(p, currency) ?? priceIn(p, currency);

export interface PlanCatalogue {
  provider: 'PAYHERE' | 'TEST';
  /** The labelled stand-in: no money moves, and every payment page says so. */
  testPayments: boolean;
  plans: PublicPlan[];
}

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
/** FREE: a discount code made the plan free, so no payment provider was involved. */
export type Provider = 'PAYHERE' | 'TEST' | 'FREE';

export interface Payment {
  id: string;
  planName: string;
  durationMonths: number;
  amountMinor: number;
  currency: string;
  provider: Provider;
  status: PaymentStatus;
  failureReason: 'CHARGEDBACK' | 'NOT_COMPLETED' | null;
  createdAt: string;
  premiumStartsAt: string | null;
  premiumEndsAt: string | null;
  /** Taken off by a discount code. */
  discountMinor: number;
  /** PayHere's payment id, for receipts and support. */
  paymentRef: string | null;
  /** The plan's regular price at checkout. */
  listPriceMinor: number | null;
  /** Refunds that went through. */
  refundedMinor: number;
  refunds: { amountMinor: number; refundedAt: string }[];
}

export interface SubscriptionPeriod {
  id: string;
  planName: string;
  status: 'ACTIVE' | 'EXPIRED' | 'CANCELLED';
  startsAt: string;
  endsAt: string;
}

export interface ModeUse {
  mode: 'PRACTICE' | 'MOCK' | 'USER_FRIENDLY';
  /** Null = unlimited. */
  limit: number | null;
  used: number;
  left: number | null;
}

/** One active plan with use per exam (for ALL: only exams already used under it). */
export interface ActivePlan {
  id: string;
  name: string;
  source: 'SUBSCRIPTION' | 'MANUAL' | 'CAMPAIGN';
  scopeType: ScopeType;
  limits: Limits;
  startsAt: string;
  endsAt: string;
  exams: { examId: string; title: string; slug: string; modes: ModeUse[] }[];
}

export interface MySubscription {
  premium: boolean;
  premiumEndsAt: string | null;
  plans: ActivePlan[];
  periods: SubscriptionPeriod[];
  payments: Payment[];
}

export interface Customer {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  address: string;
  city: string;
  country: string;
}

/**
 * Where the browser goes next: PayHere's checkout (a form POST with signed fields), the labelled test page, or (FREE)
 * straight to the result page.
 */
export interface Checkout {
  transactionId: string;
  provider: Provider;
  status: PaymentStatus;
  action: string;
  method: 'POST' | 'GET';
  fields: Record<string, string>;
}

/** contracts/openapi/subscription-service.yaml → Quote. Worked out by the server; checkout works it out again. */
export interface Quote {
  currency: Currency;
  listPriceMinor: number;
  /** Before the code: the sale price while a sale is on. */
  priceMinor: number;
  saleEndsAt: string | null;
  code: string | null;
  discountMinor: number;
  /** 0 = free with the code. */
  totalMinor: number;
}

/** Free access open to every student right now (a campaign). */
export interface Offer {
  id: string;
  name: string;
  scopeType: ScopeType;
  exams: { examId: string; title: string; slug: string }[];
  limits: Limits;
  endsAt: string;
}

export const billingKeys = {
  all: ['billing'] as const,
  plans: () => [...billingKeys.all, 'plans'] as const,
  mine: (userId: string) => [...billingKeys.all, 'mine', userId] as const,
  payment: (id: string) => [...billingKeys.all, 'payment', id] as const,
  offers: () => [...billingKeys.all, 'offers'] as const,
  history: () => [...billingKeys.all, 'history'] as const,
};

/** The student's own payments, newest first (at most 100), with refunds. */
export function useMyPayments() {
  return useQuery({
    queryKey: billingKeys.history(),
    queryFn: ({ signal }) => apiFetch<Payment[]>('/subscriptions/payments', { signal }),
  });
}

export function useOffers() {
  return useQuery({
    queryKey: billingKeys.offers(),
    queryFn: ({ signal }) => apiFetch<Offer[]>('/plans/offers', { signal }),
    staleTime: 60_000,
  });
}

export function quotePrice(planId: string, currency: Currency, code: string | null): Promise<Quote> {
  return apiFetch<Quote>('/subscriptions/quote', { method: 'POST', json: { planId, currency, code } });
}

export function usePlans(filter: { examId?: string | undefined } = {}) {
  return useQuery({
    queryKey: [...billingKeys.plans(), filter.examId ?? 'all'],
    queryFn: ({ signal }) => apiFetch<PlanCatalogue>('/plans', { signal, query: { examId: filter.examId } }),
    staleTime: 60_000,
  });
}

export function useMySubscription() {
  const { state } = useAuth();
  return useQuery({
    queryKey: billingKeys.mine(state.status === 'signedIn' ? state.user.id : 'anonymous'),
    queryFn: ({ signal }) => apiFetch<MySubscription>('/subscriptions/me', { signal }),
    enabled: state.status === 'signedIn',
  });
}

/** A payment's status. While it is PENDING, asks again every {@code pollMs} (the provider's message may still be on its way). */
export function usePayment(id: string | null, pollMs: number | false = false) {
  return useQuery({
    queryKey: billingKeys.payment(id ?? ''),
    queryFn: ({ signal }) => apiFetch<Payment>(`/subscriptions/checkout/${id}`, { signal }),
    enabled: !!id,
    refetchInterval: (query) => (pollMs && query.state.data?.status === 'PENDING' ? pollMs : false),
  });
}

export function startCheckout(
  planId: string,
  currency: Currency,
  code: string | null,
  idempotencyKey: string,
  /** Null when nothing is paid (a code makes it free); PayHere needs it. */
  customer: Customer | null,
): Promise<Checkout> {
  return apiFetch<Checkout>('/subscriptions/checkout', {
    method: 'POST',
    json: { planId, currency, code, customer },
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function completeTestPayment(transactionId: string, outcome: 'SUCCEEDED' | 'FAILED' | 'CANCELLED') {
  return apiFetch<{ result: string }>(`/payments/test/${transactionId}`, { method: 'POST', json: { outcome } });
}

/**
 * Sends the browser to PayHere: a regular form POST, as PayHere's checkout requires. The fields (including the hash)
 * come from the server; the merchant secret never reaches the browser.
 */
export function submitToProvider(checkout: Checkout, doc: Document = document) {
  const form = doc.createElement('form');
  form.method = checkout.method;
  form.action = checkout.action;
  form.hidden = true;
  for (const [name, value] of Object.entries(checkout.fields)) {
    const input = doc.createElement('input');
    input.type = 'hidden';
    input.name = name;
    input.value = value;
    form.appendChild(input);
  }
  doc.body.appendChild(form);
  form.submit();
}

/** "LKR 4,500.00" or "USD 12.00"; amounts stay exact (integers in the smallest unit). */
export function formatPrice(minor: number, currency: string): string {
  const major = Math.trunc(minor / 100);
  const cents = String(Math.abs(minor % 100)).padStart(2, '0');
  return `${currency} ${major.toLocaleString('en-US')}.${cents}`;
}

/** Shorter form for big headline prices: "LKR 4,500" when there are no cents. */
export function formatPriceShort(minor: number, currency: string): string {
  return minor % 100 === 0 ? `${currency} ${(minor / 100).toLocaleString('en-US')}` : formatPrice(minor, currency);
}

export const monthsLabel = (n: number) => (n === 12 ? '12 months' : n === 1 ? '1 month' : `${n} months`);

/** Price per month in the smallest unit, rounded down, for comparing plans of different lengths. */
export const perMonth = (priceMinor: number, months: number) => Math.floor(priceMinor / months);

const MODE_WORDS = { practice: 'Practice', mock: 'Mock exams', adaptive: 'Adaptive exams' } as const;

/** "Practice: unlimited", "Mock exams: 8 attempts", leaving out exam types the plan doesn't include. */
export function describeLimits(limits: Limits): string[] {
  return (Object.keys(MODE_WORDS) as (keyof Limits)[])
    .filter((k) => limits[k] !== 0)
    .map((k) => {
      const n = limits[k];
      return `${MODE_WORDS[k]}: ${n === null ? 'unlimited' : `${n} attempt${n === 1 ? '' : 's'}`}`;
    });
}

/** What a plan covers in a few words. */
export function describeScope(plan: Pick<PublicPlan, 'scopeType' | 'exams'>): string {
  if (plan.scopeType === 'ALL') return 'Every exam';
  if (plan.scopeType === 'EXAM') return plan.exams[0]?.title ?? 'One exam';
  return `${plan.exams.length} subjects: ${plan.exams.map((e) => e.title).join(', ')}`;
}

/** One label and tone for a student's payment, refunds included. */
export function paymentState(p: Payment): { label: string; tone: 'free' | 'warning' | 'danger' | 'neutral' } {
  if (p.status === 'SUCCEEDED') {
    if (p.refundedMinor >= p.amountMinor && p.amountMinor > 0) return { label: 'Refunded', tone: 'neutral' };
    if (p.refundedMinor > 0) return { label: 'Partly refunded', tone: 'warning' };
    return { label: p.provider === 'FREE' ? 'Free with a code' : 'Paid', tone: 'free' };
  }
  if (p.failureReason === 'CHARGEDBACK') return { label: 'Reversed', tone: 'danger' };
  return {
    PENDING: { label: 'Waiting', tone: 'warning' as const },
    FAILED: { label: 'Didn’t go through', tone: 'danger' as const },
    CANCELLED: { label: 'Cancelled', tone: 'neutral' as const },
  }[p.status];
}
