import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';

/** contracts/openapi/subscription-service.yaml → PublicPlan. Prices are set by administrators. */
export interface PublicPlan {
  id: string;
  name: string;
  description: string;
  durationMonths: 1 | 3 | 6 | 12;
  /** Smallest currency unit: 450000 = LKR 4,500.00. */
  priceMinor: number;
  currency: 'LKR' | 'USD';
}

export interface PlanCatalogue {
  provider: 'PAYHERE' | 'TEST';
  /** The labelled stand-in: no money moves, and every payment page says so. */
  testPayments: boolean;
  plans: PublicPlan[];
}

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface Payment {
  id: string;
  planName: string;
  durationMonths: number;
  amountMinor: number;
  currency: string;
  provider: 'PAYHERE' | 'TEST';
  status: PaymentStatus;
  failureReason: 'CHARGEDBACK' | 'NOT_COMPLETED' | null;
  createdAt: string;
  premiumStartsAt: string | null;
  premiumEndsAt: string | null;
}

export interface SubscriptionPeriod {
  id: string;
  planName: string;
  status: 'ACTIVE' | 'EXPIRED' | 'CANCELLED';
  startsAt: string;
  endsAt: string;
}

export interface MySubscription {
  premium: boolean;
  premiumEndsAt: string | null;
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

/** Where the browser goes next: PayHere's checkout (a form POST with signed fields) or the labelled test page. */
export interface Checkout {
  transactionId: string;
  provider: 'PAYHERE' | 'TEST';
  status: PaymentStatus;
  action: string;
  method: 'POST' | 'GET';
  fields: Record<string, string>;
}

export const billingKeys = {
  all: ['billing'] as const,
  plans: () => [...billingKeys.all, 'plans'] as const,
  mine: (userId: string) => [...billingKeys.all, 'mine', userId] as const,
  payment: (id: string) => [...billingKeys.all, 'payment', id] as const,
};

export function usePlans() {
  return useQuery({
    queryKey: billingKeys.plans(),
    queryFn: ({ signal }) => apiFetch<PlanCatalogue>('/plans', { signal }),
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

export function startCheckout(planId: string, idempotencyKey: string, customer: Customer): Promise<Checkout> {
  return apiFetch<Checkout>('/subscriptions/checkout', {
    method: 'POST',
    json: { planId, customer },
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
export const perMonth = (p: Pick<PublicPlan, 'priceMinor' | 'durationMonths'>) => Math.floor(p.priceMinor / p.durationMonths);
