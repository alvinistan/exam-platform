import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { Limits, PaymentStatus, ScopeType } from '@/features/billing/api';
import type { AdminExam } from '@/features/admin/catalog/api';

/** contracts/openapi/subscription-service.yaml → Plan. */
export interface AdminPlan {
  id: string;
  code: string;
  name: string;
  description: string;
  scopeType: ScopeType;
  categoryId: string | null;
  examIds: string[];
  durationMonths: 1 | 3 | 6 | 12;
  priceLkrMinor: number | null;
  priceUsdMinor: number | null;
  limits: Limits;
  active: boolean;
  displayOrder: number;
  version: number;
  updatedAt: string;
}

export interface PlanInput {
  code?: string;
  name: string;
  description: string;
  scopeType: ScopeType;
  categoryId: string | null;
  examIds: string[];
  durationMonths: number;
  priceLkrMinor: number | null;
  priceUsdMinor: number | null;
  /** null = unlimited, 0 = not included. */
  practiceLimit: number | null;
  mockLimit: number | null;
  adaptiveLimit: number | null;
  active: boolean;
  displayOrder: number;
}

/** contracts/openapi/subscription-service.yaml → AdminPayment. */
export interface AdminPayment {
  id: string;
  studentId: string;
  planId: string;
  planName: string;
  durationMonths: number;
  provider: 'PAYHERE' | 'TEST';
  providerRef: string | null;
  amountMinor: number;
  currency: string;
  status: PaymentStatus;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export const billingAdminKeys = {
  all: ['admin', 'billing'] as const,
  plans: () => [...billingAdminKeys.all, 'plans'] as const,
  payments: () => [...billingAdminKeys.all, 'payments'] as const,
};

export function useAdminPlans() {
  return useQuery({
    queryKey: billingAdminKeys.plans(),
    queryFn: ({ signal }) => apiFetch<AdminPlan[]>('/admin/billing/plans', { signal }),
  });
}

export function useAdminPayments() {
  return useQuery({
    queryKey: billingAdminKeys.payments(),
    queryFn: ({ signal }) => apiFetch<AdminPayment[]>('/admin/billing/payments', { signal }),
  });
}

/** Every exam an administrator can put in a plan (drafts too, so plans can be prepared before publishing). */
export function usePlanExams() {
  return useQuery({
    queryKey: [...billingAdminKeys.all, 'exams'],
    queryFn: ({ signal }) => apiFetch<{ items: AdminExam[] }>('/admin/catalog/exams', { signal, query: { page: 0, size: 100 } }),
    select: (d) => d.items.filter((e) => e.status !== 'ARCHIVED'),
  });
}

export const deletePlan = (plan: AdminPlan) => apiFetch<undefined>(`/admin/billing/plans/${plan.id}`, { method: 'DELETE' });

export const createPlan = (input: PlanInput) => apiFetch<AdminPlan>('/admin/billing/plans', { method: 'POST', json: input });

export const updatePlan = (plan: AdminPlan, input: PlanInput) =>
  apiFetch<AdminPlan>(`/admin/billing/plans/${plan.id}`, {
    method: 'PUT',
    json: input,
    headers: { 'If-Match': `"${plan.version}"` },
  });

/** "4,500.50" typed by an administrator → 450050. Null when it isn't a price. */
export function parsePrice(text: string): number | null {
  const clean = text.replace(/,/g, '').trim();
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(clean)) return null;
  const [whole = '0', cents = ''] = clean.split('.');
  return Number(whole) * 100 + Number(cents.padEnd(2, '0'));
}

/** A plan code nobody types: from the name, plus a short random tail so two "Monthly" plans never clash. */
export function planCode(name: string): string {
  const base = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 30);
  return `${base || 'plan'}-${Math.random().toString(36).slice(2, 6)}`;
}
