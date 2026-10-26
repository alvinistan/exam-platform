import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { Mode } from '@/features/exams/api';

export type RuleCode = 'FA1_CATALOGUE_POSITION' | 'FA2_WELCOME_ALLOWANCE' | 'FA4_FREE_FEATURES';

export interface Policy<P = Record<string, unknown>> {
  ruleCode: RuleCode;
  active: boolean;
  params: P;
  version: number;
  updatedAt: string;
}

export interface PositionParams {
  count: number;
  scope: 'GLOBAL' | 'CATEGORY';
}
export interface WelcomeParams {
  attempts: number;
}
export interface FeatureParams {
  modes: Mode[];
}

export interface ExamAccessRow {
  examId: string;
  title: string;
  slug: string;
  categoryId: string;
  displayOrder: number | null;
  access: 'FREE' | 'PREMIUM';
  reason: string;
}

export interface PoliciesView {
  policies: Policy[];
  freeExamIds: string[];
  exams: ExamAccessRow[];
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
  policies: () => [...accessAdminKeys.all, 'policies'] as const,
  entitlements: () => [...accessAdminKeys.all, 'entitlements'] as const,
};

export function usePolicies() {
  return useQuery({
    queryKey: accessAdminKeys.policies(),
    queryFn: ({ signal }) => apiFetch<PoliciesView>('/admin/billing/policies', { signal }),
  });
}

export function useEntitlements() {
  return useQuery({
    queryKey: accessAdminKeys.entitlements(),
    queryFn: ({ signal }) => apiFetch<Entitlement[]>('/admin/billing/entitlements', { signal }),
  });
}

export const savePolicy = (rule: RuleCode, version: number, active: boolean, params: object) =>
  apiFetch<Policy>(`/admin/billing/policies/${rule}`, {
    method: 'PUT',
    json: { active, params },
    headers: { 'If-Match': `"${version}"` },
  });

export const grantEntitlement = (studentId: string, endsAt: string, reason: string) =>
  apiFetch<Entitlement>('/admin/billing/entitlements', { method: 'POST', json: { studentId, endsAt, reason } });

export const revokeEntitlement = (id: string, reason: string) =>
  apiFetch<undefined>(`/admin/billing/entitlements/${id}/revoke`, { method: 'POST', json: { reason } });
