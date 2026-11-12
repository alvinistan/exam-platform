import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Mode } from '@/features/exams/api';

/**
 * One exam type for the caller. OPEN: an unlimited plan. PLAN: attempts left in the caller's plans (planLeft). FREE:
 * free attempts left (or would be, for a visitor). LOCKED: nothing left.
 */
export interface ModeAccess {
  mode: Mode;
  access: 'OPEN' | 'PLAN' | 'FREE' | 'LOCKED';
  freeAttempts: number;
  /** Free attempts used and left; null for anonymous visitors. */
  used: number | null;
  left: number | null;
  /** Attempts left in the caller's limited plans; null when no plan includes this exam type. */
  planLeft?: number | null;
}

/** contracts/openapi/common.yaml → AccessStatus. Decided by subscription-service on every request. */
export interface AccessStatus {
  available: boolean;
  premium: boolean;
  modes: ModeAccess[];
}

export interface MyAccess {
  premium: boolean;
  entitlement: { source: 'SUBSCRIPTION' | 'MANUAL'; endsAt: string } | null;
}

export const accessKeys = {
  all: ['access'] as const,
  exams: (userId: string, ids: string[]) => [...accessKeys.all, 'exams', userId, ids] as const,
  me: (userId: string) => [...accessKeys.all, 'me', userId] as const,
};

/**
 * Free attempts and Premium status for a set of exams, for whoever is browsing. Waits for sign-in to settle so a
 * signed-in student never briefly sees the anonymous answer.
 */
export function useExamAccess(examIds: string[]) {
  const { state } = useAuth();
  const ids = [...new Set(examIds)].sort().slice(0, 100);
  const who = state.status === 'signedIn' ? state.user.id : 'anonymous';
  return useQuery({
    queryKey: accessKeys.exams(who, ids),
    queryFn: ({ signal }) => apiFetch<Record<string, AccessStatus>>('/access/exams', { signal, query: { examIds: ids } }),
    enabled: state.status !== 'loading' && ids.length > 0,
    staleTime: 30_000,
  });
}

export function useMyAccess() {
  const { state } = useAuth();
  return useQuery({
    queryKey: accessKeys.me(state.status === 'signedIn' ? state.user.id : 'anonymous'),
    queryFn: ({ signal }) => apiFetch<MyAccess>('/access/me', { signal }),
    enabled: state.status === 'signedIn',
  });
}

/** Overall label for an exam card: Premium (yours), free to try, or Premium only. */
export function examAccessSummary(status: AccessStatus | undefined): 'PREMIUM_OPEN' | 'FREE' | 'LOCKED' | null {
  if (!status || !status.available || status.modes.length === 0) return null;
  if (status.premium) return 'PREMIUM_OPEN';
  return status.modes.some((m) => m.access === 'FREE' || m.access === 'PLAN') ? 'FREE' : 'LOCKED';
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** Plain words for one exam type: "2 of 3 free attempts left", "5 attempts left in your plan", "Unlimited with your plan". */
export function describeModeAccess(m: ModeAccess): string {
  if (m.access === 'OPEN') return 'Unlimited with your plan';
  if (m.access === 'PLAN') {
    const free = m.left ? ` + ${plural(m.left, 'free attempt')}` : '';
    return `${plural(m.planLeft ?? 0, 'attempt')} left in your plan${free}`;
  }
  if (m.left === null) return m.freeAttempts > 0 ? `${plural(m.freeAttempts, 'free attempt')}` : 'Needs a plan';
  if (m.access === 'FREE') return `${m.left} of ${plural(m.freeAttempts, 'free attempt')} left`;
  if (m.planLeft === 0) return 'Attempts in your plan used';
  return m.freeAttempts > 0 ? `Free attempts used (${m.used ?? m.freeAttempts} of ${m.freeAttempts})` : 'Needs a plan';
}
