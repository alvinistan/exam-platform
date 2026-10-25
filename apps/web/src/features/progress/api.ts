import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Mode } from '@/features/exams/api';

/** contracts/openapi/assessment-service.yaml → Progress (ADR-016). */
export type TopicStatus = 'STRONG' | 'DEVELOPING' | 'NEEDS_WORK' | 'NOT_ENOUGH';

export interface TopicProgress {
  topicId: string;
  title: string;
  answered: number;
  correct: number;
  /** All answers on the topic. */
  accuracy: number;
  /** The last 8 answers. */
  recentAccuracy: number;
  level: number;
  levelLabel: string;
  status: TopicStatus;
  /** The last level move: 1 up, −1 down, 0 none yet. */
  levelTrend: number;
  lastPracticed: string;
}

export interface ExamProgress {
  examId: string;
  answered: number;
  correct: number;
  accuracy: number;
  topics: TopicProgress[];
  /** Topics to practise next, weakest first. */
  focus: string[];
  sessions: { attemptId: string; mode: Mode; finishedAt: string; percent: number; questions: number }[];
}

export interface ExamPractised {
  examId: string;
  title: string;
  slug: string;
  lastActivity: string;
  attempts: number;
  answered: number;
  accuracy: number;
  topicsNeedingWork: number;
}

export interface FocusTopic {
  examId: string;
  examTitle: string;
  examSlug: string;
  topicId: string;
  title: string;
  level: number;
  levelLabel: string;
  recentAccuracy: number;
}

/** contracts → getProgressOverview: the dashboard's picture across all exams. */
export interface Overview {
  answered: number;
  correct: number;
  accuracy: number;
  finishedAttempts: number;
  streakDays: number;
  answeredThisWeek: number;
  /** 14 days, oldest first. */
  activity: { day: string; answered: number; correct: number }[];
  focus: FocusTopic[];
  exams: ExamPractised[];
}

export const progressKeys = {
  all: ['progress'] as const,
  overview: (userId: string) => [...progressKeys.all, 'overview', userId] as const,
  exams: (userId: string) => [...progressKeys.all, 'exams', userId] as const,
  exam: (userId: string, examId: string) => [...progressKeys.all, 'exam', userId, examId] as const,
};

function useUserId() {
  const { state } = useAuth();
  return state.status === 'signedIn' ? state.user.id : null;
}

/** The browser's time zone, so "today" and the streak follow the student's own day. */
export function timeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

export function useOverview() {
  const userId = useUserId();
  return useQuery({
    queryKey: progressKeys.overview(userId ?? 'anonymous'),
    queryFn: ({ signal }) => apiFetch<Overview>('/progress/overview', { signal, query: { tz: timeZone() } }),
    enabled: userId !== null,
  });
}

export function useExamsPractised() {
  const userId = useUserId();
  return useQuery({
    queryKey: progressKeys.exams(userId ?? 'anonymous'),
    queryFn: ({ signal }) => apiFetch<ExamPractised[]>('/progress/exams', { signal }),
    enabled: userId !== null,
  });
}

export function useExamProgress(examId: string | undefined) {
  const userId = useUserId();
  return useQuery({
    queryKey: progressKeys.exam(userId ?? 'anonymous', examId ?? ''),
    queryFn: ({ signal }) => apiFetch<ExamProgress>(`/progress/exams/${examId ?? ''}`, { signal }),
    enabled: userId !== null && !!examId,
  });
}

export const statusLabels: Record<TopicStatus, string> = {
  STRONG: 'Strong',
  DEVELOPING: 'Developing',
  NEEDS_WORK: 'Needs work',
  NOT_ENOUGH: 'Just started',
};
