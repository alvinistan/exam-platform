import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Mode } from '@/features/exams/api';

/** One exam type for the caller. OPEN: Premium. FREE: free attempts left (or would be, for a visitor). */
export interface ModeAccess {
  mode: Mode;
  access: 'OPEN' | 'FREE' | 'LOCKED';
  freeAttempts: number;
  /** Null for anonymous visitors and Premium students. */
  used: number | null;
  left: number | null;
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
  return status.modes.some((m) => m.access === 'FREE') ? 'FREE' : 'LOCKED';
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** Plain words for one exam type: "2 of 3 free attempts left", "Free attempts used", "Unlimited with Premium". */
export function describeModeAccess(m: ModeAccess): string {
  if (m.access === 'OPEN') return 'Unlimited with your Premium access';
  if (m.left === null) return m.freeAttempts > 0 ? `${plural(m.freeAttempts, 'free attempt')}` : 'Premium only';
  if (m.access === 'FREE') return `${m.left} of ${plural(m.freeAttempts, 'free attempt')} left`;
  return m.freeAttempts > 0 ? `Free attempts used (${m.used ?? m.freeAttempts} of ${m.freeAttempts})` : 'Premium only';
}
