import type { Mode } from '@/features/exams/api';

/** Exam types in addresses: /exams/:slug/start/practice, …/mock, …/adaptive. */
export const modeSlugs: Record<Mode, string> = {
  PRACTICE: 'practice',
  MOCK: 'mock',
  USER_FRIENDLY: 'adaptive',
};

export function modeFromSlug(slug: string | undefined): Mode | undefined {
  return (Object.entries(modeSlugs) as [Mode, string][]).find(([, s]) => s === slug)?.[0];
}

export const startPath = (examSlug: string, mode: Mode) => `/exams/${examSlug}/start/${modeSlugs[mode]}`;

/** Question-count choices offered to students, never above the exam type's maximum. */
export function countChoices(maxCount: number): number[] {
  const choices = [5, 10, 20, 30].filter((n) => n <= maxCount);
  if (!choices.includes(maxCount) && maxCount < 30) choices.push(maxCount);
  return choices.sort((a, b) => a - b);
}

/** Minute choices; a mock exam can't be longer than its time limit. */
export function minuteChoices(limitSeconds: number | null | undefined): number[] {
  const limit = limitSeconds ? Math.floor(limitSeconds / 60) : Infinity;
  const choices = [10, 20, 30].filter((n) => n <= limit);
  if (choices.length === 0 && Number.isFinite(limit) && limit >= 1) choices.push(limit);
  return choices;
}

export const difficultyLabels = { SIMPLE: 'Easy', MEDIUM: 'Medium', HARD: 'Hard' } as const;

/** A student's level on a topic (ADR-016), shown with the same words as question difficulty. */
export const levelLabels: Record<number, string> = { 1: 'Easy', 2: 'Medium', 3: 'Hard' };

/** "12:05" or "1:02:05". */
export function clock(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}
