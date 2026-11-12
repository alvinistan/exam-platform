import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { Limits, ScopeType } from '@/features/billing/api';
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
  /** All null when there is no sale. */
  sale: { priceLkrMinor: number | null; priceUsdMinor: number | null; startsAt: string | null; endsAt: string | null };
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
  /** Lower than the regular price, between saleStartsAt and saleEndsAt. */
  salePriceLkrMinor: number | null;
  salePriceUsdMinor: number | null;
  saleStartsAt: string | null;
  saleEndsAt: string | null;
}

/** contracts/openapi/subscription-service.yaml → DiscountCode. */
export interface DiscountCode {
  id: string;
  code: string;
  description: string;
  percentOff: number | null;
  amountOffLkrMinor: number | null;
  amountOffUsdMinor: number | null;
  allPlans: boolean;
  planIds: string[];
  startsAt: string;
  endsAt: string;
  /** Null = no overall limit. */
  maxUses: number | null;
  maxUsesPerStudent: number;
  active: boolean;
  version: number;
  /** Payments made with it that are pending or succeeded. */
  uses: number;
}

export interface DiscountCodeInput {
  code?: string;
  description: string;
  percentOff: number | null;
  amountOffLkrMinor: number | null;
  amountOffUsdMinor: number | null;
  allPlans: boolean;
  planIds: string[];
  startsAt: string | null;
  endsAt: string;
  maxUses: number | null;
  maxUsesPerStudent: number;
  active: boolean;
}

/** contracts/openapi/subscription-service.yaml → Campaign. */
export interface Campaign {
  id: string;
  name: string;
  scopeType: ScopeType;
  categoryId: string | null;
  examIds: string[];
  limits: Limits;
  /** ALL: every student between startsAt and endsAt. SELECTED: chosen students, each for durationMonths. */
  audience: 'ALL' | 'SELECTED';
  startsAt: string;
  endsAt: string | null;
  durationMonths: number | null;
  endedAt: string | null;
  createdBy: string;
  createdAt: string;
  /** Students who have it (for ALL, those who have used it). */
  students: number;
}

export interface CampaignInput {
  name: string;
  scopeType: ScopeType;
  categoryId: string | null;
  examIds: string[];
  practiceLimit: number | null;
  mockLimit: number | null;
  adaptiveLimit: number | null;
  audience: 'ALL' | 'SELECTED';
  startsAt: string | null;
  endsAt: string | null;
  durationMonths: number | null;
  studentIds: string[];
}

export const billingAdminKeys = {
  all: ['admin', 'billing'] as const,
  plans: () => [...billingAdminKeys.all, 'plans'] as const,
  payments: () => [...billingAdminKeys.all, 'payments'] as const,
  codes: () => [...billingAdminKeys.all, 'codes'] as const,
  campaigns: () => [...billingAdminKeys.all, 'campaigns'] as const,
};

export function useDiscountCodes() {
  return useQuery({
    queryKey: billingAdminKeys.codes(),
    queryFn: ({ signal }) => apiFetch<DiscountCode[]>('/admin/billing/discount-codes', { signal }),
  });
}

export function useCampaigns() {
  return useQuery({
    queryKey: billingAdminKeys.campaigns(),
    queryFn: ({ signal }) => apiFetch<Campaign[]>('/admin/billing/campaigns', { signal }),
  });
}

export const createDiscountCode = (input: DiscountCodeInput) =>
  apiFetch<DiscountCode>('/admin/billing/discount-codes', { method: 'POST', json: input });

export const updateDiscountCode = (code: DiscountCode, input: DiscountCodeInput) =>
  apiFetch<DiscountCode>(`/admin/billing/discount-codes/${code.id}`, {
    method: 'PUT',
    json: input,
    headers: { 'If-Match': `"${code.version}"` },
  });

export const createCampaign = (input: CampaignInput) =>
  apiFetch<Campaign>('/admin/billing/campaigns', { method: 'POST', json: input });

export const addCampaignStudents = (campaign: Campaign, studentIds: string[]) =>
  apiFetch<Campaign>(`/admin/billing/campaigns/${campaign.id}/students`, { method: 'POST', json: { studentIds } });

export const endCampaign = (campaign: Campaign) =>
  apiFetch<Campaign>(`/admin/billing/campaigns/${campaign.id}/end`, { method: 'POST' });

/** Whether a campaign is giving access now (not ended early, and for ALL, not past its end). */
export function campaignOpen(c: Campaign, now = Date.now()): boolean {
  if (c.endedAt) return false;
  return c.endsAt === null || Date.parse(c.endsAt) > now;
}

export function useAdminPlans() {
  return useQuery({
    queryKey: billingAdminKeys.plans(),
    queryFn: ({ signal }) => apiFetch<AdminPlan[]>('/admin/billing/plans', { signal }),
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

/** An ISO time → the value a datetime-local input shows, in the administrator's own time zone. */
export function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** A datetime-local value (the administrator's time zone) → ISO time; null when empty or not a time. */
export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const t = new Date(value);
  return Number.isNaN(t.getTime()) ? null : t.toISOString();
}
