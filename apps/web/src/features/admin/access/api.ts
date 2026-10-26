import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { Mode } from '@/features/exams/api';

/** contracts/openapi/subscription-service.yaml → ExamFreeAttempts. */
export interface ExamFreeAttempts {
  examId: string;
  title: string;
  slug: string;
  modes: Mode[];
  freeAttempts: Partial<Record<Mode, number>>;
}

export interface Entitlement {
  id: string;
  studentId: string;
  source: 'SUBSCRIPTION' | 'MANUAL';
  state: 'ACTIVE' | 'SCHEDULED' | 'EXPIRED' | 'REVOKED';
  startsAt: string;
  endsAt: string;
  grantReason: string | null;
  grantedBy: string | null;
  revokedAt: string | null;
  revokedReason: string | null;
  createdAt: string;
}

export const accessAdminKeys = {
  all: ['admin', 'access'] as const,
  freeAttempts: () => [...accessAdminKeys.all, 'free-attempts'] as const,
  entitlements: () => [...accessAdminKeys.all, 'entitlements'] as const,
};

export function useFreeAttempts() {
  return useQuery({
    queryKey: accessAdminKeys.freeAttempts(),
    queryFn: ({ signal }) => apiFetch<ExamFreeAttempts[]>('/admin/billing/free-attempts', { signal }),
  });
}

export function useEntitlements() {
  return useQuery({
    queryKey: accessAdminKeys.entitlements(),
    queryFn: ({ signal }) => apiFetch<Entitlement[]>('/admin/billing/entitlements', { signal }),
  });
}

export const grantEntitlement = (studentId: string, endsAt: string, reason: string) =>
  apiFetch<Entitlement>('/admin/billing/entitlements', { method: 'POST', json: { studentId, endsAt, reason } });

export const revokeEntitlement = (id: string, reason: string) =>
  apiFetch<undefined>(`/admin/billing/entitlements/${id}/revoke`, { method: 'POST', json: { reason } });
