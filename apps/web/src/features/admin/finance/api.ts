import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { BadgeTone } from '@/components/ui';
import type { Currency, PaymentStatus, Provider } from '@/features/billing/api';

/** contracts/openapi/subscription-service.yaml → FinancePayment. */
export interface FinancePayment {
  id: string;
  studentId: string;
  planId: string;
  planName: string;
  durationMonths: number;
  provider: Provider;
  providerRef: string | null;
  amountMinor: number;
  currency: Currency;
  status: PaymentStatus;
  failureReason: string | null;
  listPriceMinor: number | null;
  discountMinor: number;
  discountCode: string | null;
  /** Refunds that went through. */
  refundedMinor: number;
  /** Refunds sent to PayHere and not answered yet. */
  refundPendingMinor: number;
  refundState: 'NONE' | 'PARTIAL' | 'FULL';
  periodStatus: 'ACTIVE' | 'EXPIRED' | 'CANCELLED' | null;
  periodStartsAt: string | null;
  periodEndsAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Refund {
  id: string;
  transactionId: string;
  amountMinor: number;
  currency: Currency;
  reason: string;
  method: 'PAYHERE_API' | 'MANUAL';
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  providerRef: string | null;
  failureReason: string | null;
  stopAccess: boolean;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentDetail {
  payment: FinancePayment;
  refunds: Refund[];
  /** What can still be refunded: paid − refunds pending or done. */
  refundableMinor: number;
  /** Whether a refund of this payment can go through PayHere's API. */
  apiRefunds: boolean;
}

export type PaymentFilterStatus = 'PAID' | 'FREE' | 'PENDING' | 'FAILED' | 'CANCELLED' | 'REFUNDED';

export interface PaymentFilter {
  from: string;
  to: string;
  status: PaymentFilterStatus | '';
  currency: Currency | '';
  planId: string;
  code: string;
  q: string;
  studentId: string;
}

export const emptyFilter: PaymentFilter = {
  from: '',
  to: '',
  status: '',
  currency: '',
  planId: '',
  code: '',
  q: '',
  studentId: '',
};

export interface PaymentPage {
  items: FinancePayment[];
  page: { page: number; size: number; totalElements: number; totalPages: number };
}

export interface Money {
  currency: Currency;
  paidMinor: number;
  refundedMinor: number;
  netMinor: number;
  discountMinor: number;
  paidPayments: number;
  refunds: number;
}

export interface FinanceSummary {
  from: string;
  to: string;
  tz: string;
  money: Money[];
  payments: { paid: number; free: number; pending: number; failed: number; cancelled: number };
  byPlan: { planName: string; currency: Currency; payments: number; paidMinor: number }[];
  days: { date: string; currency: Currency; paidMinor: number; refundedMinor: number; payments: number }[];
  subscribers: {
    activeNow: number;
    endingIn7Days: number;
    endingIn30Days: number;
    newBuyers: number;
    returningBuyers: number;
    freeAccessNow: number;
  };
  endingSoon: { studentId: string; planName: string; endsAt: string }[];
  apiRefunds: boolean;
}

/** The administrator's own time zone: reports group by their calendar days. */
export const timeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

export const financeKeys = {
  all: ['admin-finance'] as const,
  summary: (from: string, to: string) => [...financeKeys.all, 'summary', from, to] as const,
  payments: (f: PaymentFilter, page: number, size: number) => [...financeKeys.all, 'payments', f, page, size] as const,
  payment: (id: string) => [...financeKeys.all, 'payment', id] as const,
};

const query = (f: PaymentFilter) => {
  const q: Record<string, string> = { tz: timeZone() };
  for (const [k, v] of Object.entries(f)) if (v) q[k] = String(v).trim();
  return q;
};

export function useFinanceSummary(from: string, to: string) {
  return useQuery({
    queryKey: financeKeys.summary(from, to),
    queryFn: ({ signal }) =>
      apiFetch<FinanceSummary>('/admin/billing/finance/summary', { signal, query: { from, to, tz: timeZone() } }),
    placeholderData: keepPreviousData,
  });
}

export function usePayments(f: PaymentFilter, page: number, size = 25) {
  return useQuery({
    queryKey: financeKeys.payments(f, page, size),
    queryFn: ({ signal }) =>
      apiFetch<PaymentPage>('/admin/billing/payments', {
        signal,
        query: { ...query(f), page: String(page), size: String(size) },
      }),
    placeholderData: keepPreviousData,
  });
}

export function usePaymentDetail(id: string) {
  return useQuery({
    queryKey: financeKeys.payment(id),
    queryFn: ({ signal }) => apiFetch<PaymentDetail>(`/admin/billing/payments/${id}`, { signal }),
  });
}

export interface RefundInput {
  amountMinor: number | null;
  reason: string;
  stopAccess: boolean;
  method: 'PAYHERE_API' | 'MANUAL' | null;
  reference: string | null;
}

export const refundPayment = (transactionId: string, input: RefundInput) =>
  apiFetch<Refund>(`/admin/billing/payments/${transactionId}/refunds`, { method: 'POST', json: input });

export const resolveRefund = (refundId: string, outcome: 'SUCCEEDED' | 'FAILED', reference: string | null, note: string | null) =>
  apiFetch<Refund>(`/admin/billing/refunds/${refundId}/resolve`, { method: 'POST', json: { outcome, reference, note } });

/** Names and emails of students (identity-service's contacts lookup), at most 100 ids per call. */
export async function namesFor(ids: string[]): Promise<Map<string, { displayName: string; email: string }>> {
  const unique = [...new Set(ids)];
  const out = new Map<string, { displayName: string; email: string }>();
  for (let i = 0; i < unique.length; i += 100) {
    const rows = await apiFetch<{ id: string; displayName: string; email: string }[]>('/admin/users/contacts', {
      query: { ids: unique.slice(i, i + 100) },
    });
    for (const r of rows) out.set(r.id, { displayName: r.displayName, email: r.email });
  }
  return out;
}

const MAX_EXPORT = 5000;

/**
 * Every payment matching the filter as CSV (at most 5,000, newest first), with the student's name and email, for the
 * accountant. Amounts are in currency units with two decimals.
 */
export async function exportPaymentsCsv(f: PaymentFilter): Promise<{ csv: string; rows: number; truncated: boolean }> {
  const items: FinancePayment[] = [];
  let total = 0;
  for (let page = 0; items.length < MAX_EXPORT; page++) {
    const r = await apiFetch<PaymentPage>('/admin/billing/payments', { query: { ...query(f), page: String(page), size: '100' } });
    total = r.page.totalElements;
    items.push(...r.items);
    if (page + 1 >= r.page.totalPages) break;
  }
  const names = await namesFor(items.map((p) => p.studentId));
  const amount = (minor: number) => (minor / 100).toFixed(2);
  const header = [
    'Date',
    'Payment ID',
    'PayHere payment ID',
    'Student',
    'Email',
    'Plan',
    'Months',
    'Currency',
    'List price',
    'Discount',
    'Code',
    'Paid',
    'Refunded',
    'Net',
    'Status',
    'Provider',
  ];
  const rows = items.map((p) => [
    p.createdAt,
    p.id,
    p.provider === 'PAYHERE' ? (p.providerRef ?? '') : '',
    names.get(p.studentId)?.displayName ?? '',
    names.get(p.studentId)?.email ?? '',
    p.planName,
    String(p.durationMonths),
    p.currency,
    p.listPriceMinor === null ? '' : amount(p.listPriceMinor),
    amount(p.discountMinor),
    p.discountCode ?? '',
    p.status === 'SUCCEEDED' ? amount(p.amountMinor) : '0.00',
    amount(p.refundedMinor),
    p.status === 'SUCCEEDED' ? amount(p.amountMinor - p.refundedMinor) : '0.00',
    paymentStateLabel(p),
    p.provider,
  ]);
  const csv = [header, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n');
  return { csv: '﻿' + csv, rows: items.length, truncated: total > items.length };
}

/** Quotes a CSV cell, and stops spreadsheet apps treating text that starts with = + - @ as a formula. */
export function csvCell(value: string): string {
  const safe = /^[=+\-@\t\r]/.test(value) && !/^-?\d+(\.\d+)?$/.test(value) ? `'${value}` : value;
  return /[",\r\n]/.test(safe) || safe !== value ? `"${safe.replace(/"/g, '""')}"` : safe;
}

/** One label for a payment's state, refunds included. */
export function paymentStateLabel(p: Pick<FinancePayment, 'status' | 'refundState' | 'provider' | 'failureReason'>): string {
  if (p.status === 'SUCCEEDED') {
    if (p.refundState === 'FULL') return 'Refunded';
    if (p.refundState === 'PARTIAL') return 'Partly refunded';
    return p.provider === 'FREE' ? 'Free with a code' : 'Paid';
  }
  if (p.failureReason === 'CHARGEDBACK') return 'Charged back';
  return { PENDING: 'Waiting', FAILED: 'Failed', CANCELLED: 'Cancelled' }[p.status];
}

/** Badge tone for a payment's state: paid green, refunded or waiting amber, failed red. */
export function stateTone(p: Pick<FinancePayment, 'status' | 'refundState' | 'failureReason'>): BadgeTone {
  if (p.status === 'SUCCEEDED') return p.refundState === 'NONE' ? 'free' : 'warning';
  if (p.status === 'PENDING') return 'warning';
  if (p.status === 'FAILED' || p.failureReason === 'CHARGEDBACK') return 'danger';
  return 'neutral';
}
