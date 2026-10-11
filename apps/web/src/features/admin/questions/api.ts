import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { PageMeta } from '@/features/exams/api';

export type Bank = 'TRIAL' | 'MAIN';
export type QuestionStatus = 'DRAFT' | 'REVIEWED' | 'PUBLISHED' | 'ARCHIVED';
export type Difficulty = 'SIMPLE' | 'MEDIUM' | 'HARD';
export type AnswerType = 'SINGLE' | 'MULTI';

export interface Definition {
  term: string;
  meaning: string;
}

/** contracts/openapi/catalog-service.yaml → QuestionSummary */
export interface QuestionSummary {
  id: string;
  bank: Bank;
  status: QuestionStatus;
  topicId: string;
  topicTitle: string;
  moduleTitle: string;
  subjectTitle: string;
  difficulty: Difficulty;
  answerType: AnswerType;
  stemText: string;
  versionNo: number;
  hasDraftChanges: boolean;
  tags: string[];
  updatedAt: string;
  updatedBy: string;
}

export interface QuestionOption {
  label: string;
  bodyHtml: string;
  mediaKey: string | null;
  mediaUrl: string | null;
  mediaAlt: string | null;
  isCorrect: boolean;
  misconceptionId: string | null;
}

/** contracts/openapi/catalog-service.yaml → QuestionAdmin */
export interface QuestionDetail {
  id: string;
  bank: Bank;
  status: QuestionStatus;
  topicId: string;
  topic: { subjectId: string; subjectTitle: string; moduleId: string; moduleTitle: string; topicTitle: string; active: boolean };
  answerType: AnswerType;
  stemHtml: string;
  stemMediaKey: string | null;
  stemMediaUrl: string | null;
  stemMediaAlt: string | null;
  options: QuestionOption[];
  explanationHtml: string;
  definitions: Definition[];
  keyPoints: string[];
  difficulty: Difficulty;
  estSeconds: number;
  marks: number;
  tags: string[];
  versionNo: number;
  publishedVersionNo: number | null;
  hasDraftChanges: boolean;
  version: number;
  validation: { valid: boolean; problems: string[] };
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  updatedBy: string;
  history: { versionNo: number; published: boolean; publishedAt: string | null; createdBy: string; createdAt: string }[];
}

export interface OptionInput {
  bodyHtml: string;
  mediaKey: string | null;
  mediaAlt: string | null;
  isCorrect: boolean;
  misconceptionId: string | null;
}

export interface QuestionInput {
  topicId: string;
  bank: Bank;
  answerType: AnswerType;
  stemHtml: string;
  stemMediaKey: string | null;
  stemMediaAlt: string | null;
  options: OptionInput[];
  explanationHtml: string;
  definitions: Definition[];
  keyPoints: string[];
  difficulty: Difficulty;
  estSeconds: number;
  marks: number;
  tags: string[];
}

export interface QuestionFilters {
  q: string;
  tag: string;
  bank: Bank | '';
  examId: string;
  subjectId: string;
  topicId: string;
  status: QuestionStatus | '';
  difficulty: Difficulty | '';
  sort: '-updatedAt' | 'updatedAt' | 'difficulty';
  page: number;
  size: 25 | 50 | 100;
}

export interface Misconception {
  id: string;
  topicId: string | null;
  title: string;
  description: string;
}

export interface StoredMedia {
  key: string;
  contentType: string;
  width: number;
  height: number;
  url: string;
}

export interface ImportReport {
  importId: string;
  examId: string;
  bank: Bank;
  fileName: string;
  totalRows: number;
  validRows: number;
  errors: { row: number; column: string; message: string }[];
  status: 'PENDING' | 'CONFIRMED';
  expiresAt: string;
  createdQuestionIds: string[];
}

export const questionKeys = {
  all: ['admin', 'questions'] as const,
  list: (f: QuestionFilters) => [...questionKeys.all, 'list', f] as const,
  detail: (id: string) => [...questionKeys.all, id] as const,
  misconceptions: (topicId: string) => ['admin', 'misconceptions', topicId] as const,
  names: (ids: string[]) => ['admin', 'user-names', ids] as const,
};

export function useQuestions(f: QuestionFilters) {
  return useQuery({
    queryKey: questionKeys.list(f),
    queryFn: ({ signal }) =>
      apiFetch<{ items: QuestionSummary[]; page: PageMeta }>('/admin/catalog/questions', {
        signal,
        query: {
          page: f.page,
          size: f.size,
          q: f.q || undefined,
          tag: f.tag || undefined,
          bank: f.bank || undefined,
          examId: f.examId || undefined,
          subjectId: f.subjectId || undefined,
          topicId: f.topicId || undefined,
          status: f.status || undefined,
          difficulty: f.difficulty || undefined,
          sort: f.sort,
        },
      }),
    placeholderData: keepPreviousData,
  });
}

export function useQuestion(id: string | undefined) {
  return useQuery({
    queryKey: questionKeys.detail(id ?? 'new'),
    queryFn: ({ signal }) => apiFetch<QuestionDetail>(`/admin/catalog/questions/${id ?? ''}`, { signal }),
    enabled: id !== undefined,
  });
}

export function useMisconceptions(topicId: string) {
  return useQuery({
    queryKey: questionKeys.misconceptions(topicId),
    queryFn: ({ signal }) => apiFetch<Misconception[]>('/admin/catalog/misconceptions', { signal, query: { topicId } }),
    enabled: topicId !== '',
  });
}

/** Display names for audit columns (identity-service); unknown IDs simply have no entry. */
export function useUserNames(ids: string[]) {
  const unique = [...new Set(ids)].sort();
  return useQuery({
    queryKey: questionKeys.names(unique),
    queryFn: ({ signal }) =>
      apiFetch<{ id: string; displayName: string }[]>('/admin/users/names', { signal, query: { ids: unique } }),
    enabled: unique.length > 0,
    staleTime: 5 * 60_000,
    select: (rows) => new Map(rows.map((r) => [r.id, r.displayName])),
  });
}

const ifMatch = (version: number) => ({ 'If-Match': `"${version}"` });

export const createQuestion = (input: QuestionInput) =>
  apiFetch<QuestionDetail>('/admin/catalog/questions', { method: 'POST', json: input });

export const saveQuestion = (id: string, version: number, input: QuestionInput) =>
  apiFetch<QuestionDetail>(`/admin/catalog/questions/${id}`, { method: 'PUT', json: input, headers: ifMatch(version) });

export const moveQuestion = (id: string, to: QuestionStatus) =>
  apiFetch<QuestionDetail>(`/admin/catalog/questions/${id}/transitions`, { method: 'POST', json: { to } });

export const discardDraft = (id: string) =>
  apiFetch<QuestionDetail>(`/admin/catalog/questions/${id}/discard-draft`, { method: 'POST' });

export type BulkAction =
  | { action: 'SET_STATUS'; status: QuestionStatus }
  | { action: 'MOVE_TOPIC'; topicId: string }
  | { action: 'CHANGE_BANK'; bank: Bank };

export const bulkQuestions = (questionIds: string[], action: BulkAction) =>
  apiFetch<{ updated: number; createdIds: string[] }>('/admin/catalog/questions/bulk', {
    method: 'POST',
    json: { questionIds, ...action },
  });

export const createMisconception = (input: { topicId: string | null; title: string; description: string }) =>
  apiFetch<Misconception>('/admin/catalog/misconceptions', { method: 'POST', json: input });

export function uploadImage(file: File) {
  const form = new FormData();
  form.append('file', file);
  return apiFetch<StoredMedia>('/admin/catalog/media', { method: 'POST', form });
}

export function uploadImport(file: File, examId: string, bank: Bank) {
  const form = new FormData();
  form.append('file', file);
  form.append('examId', examId);
  form.append('bank', bank);
  return apiFetch<ImportReport>('/admin/catalog/question-imports', { method: 'POST', form });
}

export const confirmImport = (importId: string) =>
  apiFetch<ImportReport>(`/admin/catalog/question-imports/${importId}/confirm`, { method: 'POST' });

export const statusLabels: Record<QuestionStatus, string> = {
  DRAFT: 'Draft',
  REVIEWED: 'Reviewed',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
};

export const difficultyLabels: Record<Difficulty, string> = { SIMPLE: 'Simple', MEDIUM: 'Medium', HARD: 'Hard' };

export const bankLabels: Record<Bank, string> = { MAIN: 'Main bank', TRIAL: 'Trial bank' };
