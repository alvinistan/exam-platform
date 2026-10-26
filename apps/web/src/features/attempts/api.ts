import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Mode } from '@/features/exams/api';

/** contracts/openapi/assessment-service.yaml. Option labels are display letters in the order shown. */
export interface Review {
  /** Null when unanswered. */
  correct: boolean | null;
  correctLabels: string[];
  explanationHtml: string | null;
  keyPoints: string[];
  definitions: { term: string; meaning: string }[];
  awarded: number;
  marks: number;
}

export interface AttemptQuestion {
  position: number;
  topicId: string;
  topicTitle: string;
  difficulty: 'SIMPLE' | 'MEDIUM' | 'HARD';
  answerType: 'SINGLE' | 'MULTI';
  estSeconds: number;
  stemHtml: string;
  stemMediaUrl: string | null;
  stemMediaAlt: string | null;
  options: { label: string; bodyHtml: string; mediaUrl: string | null; mediaAlt: string | null }[];
  selected: string[];
  flagged: boolean;
  answered: boolean;
  /** Present once the answer was checked, or the attempt is finished. */
  review: Review | null;
}

export interface TopicResult {
  topicId: string;
  title: string;
  total: number;
  correct: number;
  incorrect: number;
  unanswered: number;
  percent: number;
}

export interface AttemptResult {
  score: number;
  maxScore: number;
  percent: number;
  correct: number;
  incorrect: number;
  unanswered: number;
  passMarkPct: number | null;
  passed: boolean | null;
  durationSeconds: number;
  byTopic: TopicResult[];
}

export interface Attempt {
  id: string;
  examId: string;
  examTitle: string;
  examSlug: string;
  mode: Mode;
  status: 'IN_PROGRESS' | 'SCORED';
  finishReason: 'SUBMITTED' | 'EXPIRED' | 'ABANDONED' | null;
  feedback: 'IMMEDIATE' | 'AFTER_SUBMIT';
  sizeBy: 'COUNT' | 'TIME';
  requestedCount: number | null;
  requestedMinutes: number | null;
  estimatedSeconds: number;
  startedAt: string;
  expiresAt: string | null;
  finishedAt: string | null;
  serverNow: string;
  topics: { id: string; title: string }[];
  questions: AttemptQuestion[];
  result: AttemptResult | null;
}

export interface AttemptSummary {
  id: string;
  examId: string;
  examTitle: string;
  examSlug: string;
  mode: Mode;
  status: 'IN_PROGRESS' | 'SCORED';
  finishReason: 'SUBMITTED' | 'EXPIRED' | 'ABANDONED' | null;
  startedAt: string;
  expiresAt: string | null;
  finishedAt: string | null;
  questionCount: number;
  score: number | null;
  maxScore: number | null;
  percent: number | null;
  correct: number | null;
  incorrect: number | null;
  unanswered: number | null;
}

export interface StartInput {
  examId: string;
  mode: Mode;
  topicIds: string[];
  sizeBy: 'COUNT' | 'TIME';
  questionCount?: number;
  minutes?: number;
}

export const attemptKeys = {
  all: ['attempts'] as const,
  one: (id: string) => [...attemptKeys.all, 'one', id] as const,
  histories: () => [...attemptKeys.all, 'history'] as const,
  history: (userId: string, examId: string | undefined) => [...attemptKeys.histories(), userId, examId ?? 'all'] as const,
};

export const startAttempt = (input: StartInput, idempotencyKey: string) =>
  apiFetch<Attempt>('/attempts', { method: 'POST', json: input, headers: { 'Idempotency-Key': idempotencyKey } });

export const saveAnswer = (
  attemptId: string,
  position: number,
  body: { selected?: string[]; flagged?: boolean; timeSpentMs?: number },
) => apiFetch<AttemptQuestion>(`/attempts/${attemptId}/answers/${position}`, { method: 'PUT', json: body });

export const submitAttempt = (attemptId: string) => apiFetch<Attempt>(`/attempts/${attemptId}/submit`, { method: 'POST' });

export function useAttempt(id: string) {
  return useQuery({
    queryKey: attemptKeys.one(id),
    queryFn: ({ signal }) => apiFetch<Attempt>(`/attempts/${id}`, { signal }),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

export function useAttemptHistory(examId?: string) {
  const { state } = useAuth();
  const userId = state.status === 'signedIn' ? state.user.id : 'anonymous';
  return useQuery({
    queryKey: attemptKeys.history(userId, examId),
    queryFn: ({ signal }) => apiFetch<AttemptSummary[]>('/attempts', { signal, query: { examId } }),
    enabled: state.status === 'signedIn',
    placeholderData: keepPreviousData,
  });
}

/** How far the client clock is from the server's, so a countdown follows the server (ADR-010). */
export function clockOffsetMs(attempt: Pick<Attempt, 'serverNow'>, receivedAt = Date.now()): number {
  return new Date(attempt.serverNow).getTime() - receivedAt;
}
