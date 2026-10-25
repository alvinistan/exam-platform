import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Mode } from '@/features/exams/api';

/** contracts/openapi/common.yaml → AccessStatus. Decided by subscription-service on every request. */
export interface AccessStatus {
  access: 'FREE' | 'PREMIUM_UNLOCKED' | 'LOCKED';
  /** e.g. FA1_POSITION_2_OF_6, FA5_KEPT_FREE, ACTIVE_ENTITLEMENT, WELCOME_ALLOWANCE, SUBSCRIPTION_REQUIRED. */
  reason: string;
  freeModes: Mode[];
  welcomeAttemptsLeft: number | null;
}

export interface MyAccess {
  premium: boolean;
  entitlement: { source: 'SUBSCRIPTION' | 'MANUAL'; endsAt: string } | null;
  welcomeAttemptsLeft: number | null;
  freeExamCount: number;
}

export const accessKeys = {
  all: ['access'] as const,
  exams: (userId: string, ids: string[]) => [...accessKeys.all, 'exams', userId, ids] as const,
  me: (userId: string) => [...accessKeys.all, 'me', userId] as const,
};

/**
 * Free/premium status for a set of exams, for whoever is browsing (anonymous visitors get the policy view). Waits
 * for sign-in to settle so a signed-in student never briefly sees the anonymous answer.
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

/** Plain-language reason, for administrators and for students who wonder why. */
export function describeReason(reason: string): string {
  const position = /^FA1_(CATEGORY_)?POSITION_(\d+)_OF_(\d+)$/.exec(reason);
  if (position) {
    return position[1]
      ? `Free: position ${position[2]} of the first ${position[3]} in its category`
      : `Free: position ${position[2]} of the first ${position[3]} in the catalogue`;
  }
  switch (reason) {
    case 'FA5_KEPT_FREE':
      return 'Free for you: you started it while it was free';
    case 'ACTIVE_ENTITLEMENT':
      return 'Unlocked by your Premium access';
    case 'WELCOME_ALLOWANCE':
      return 'You can try it with your welcome attempts';
    case 'EXAM_UNAVAILABLE':
      return 'Not available';
    default:
      return 'Needs Premium access';
  }
}
