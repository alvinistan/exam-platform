import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { Mode, PageMeta } from '@/features/exams/api';

export type ExamStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type ContentStatus = 'ACTIVE' | 'ARCHIVED';

/** The guided setup's steps, in order. The server tags every publishing problem with the step that fixes it. */
export type SetupStep = 'DETAILS' | 'STRUCTURE' | 'QUESTIONS' | 'MODES';

export interface SetupIssue {
  step: SetupStep;
  message: string;
}

export interface Readiness {
  ready: boolean;
  blockers: string[];
  issues: SetupIssue[];
}

/** contracts/openapi/catalog-service.yaml → AdminExam */
export interface AdminExam {
  id: string;
  title: string;
  slug: string;
  categoryId: string;
  shortDescription: string;
  featured: boolean;
  displayOrder: number | null;
  modes: Mode[];
  status: ExamStatus;
  publishedAt: string | null;
  updatedAt: string;
  version: number;
  publishedQuestionCount: number;
  trialQuestionCount: number;
  topicCount: number;
  publishReadiness: Readiness;
  /** Main-bank questions in the exam's topics that aren't ready yet (draft or reviewed). */
  draftQuestionCount: number;
  subjectCount: number;
}

export interface ModeSettings {
  mode: Mode;
  enabled: boolean;
  defaultCount: number;
  minCount: number;
  maxCount: number;
  timeLimitSeconds: number | null;
  perQuestionSeconds: number | null;
  difficultyMix: { simple: number; medium: number; hard: number };
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  feedbackTiming: 'IMMEDIATE' | 'AFTER_SUBMIT';
  resultVisibility: 'SCORE_ONLY' | 'SCORE_AND_ANSWERS' | 'FULL';
  negativeMark: number;
  passMarkPct: number | null;
  allowTopicChoice: boolean;
  adaptiveEnabled: boolean;
  maxRetakes: number | null;
  /** Attempts of this exam type a student may start without Premium. */
  freeAttempts: number;
  version: number;
  /** False while the mode has never been saved: the values are suggestions. */
  saved: boolean;
}

export type ModeInput = Omit<ModeSettings, 'mode' | 'version' | 'saved'>;

export interface AdminExamDetail extends AdminExam {
  instructions: string;
  seoTitle: string | null;
  seoDescription: string | null;
  subjectIds: string[];
  modeSettings: ModeSettings[];
}

export interface ExamInput {
  title: string;
  slug: string;
  categoryId: string;
  shortDescription: string;
  instructions: string;
  seoTitle: string;
  seoDescription: string;
  displayOrder: number | null;
  featured: boolean;
  subjectIds: string[];
}

export interface AdminCategory {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  displayOrder: number;
  status: ContentStatus;
  version: number;
  examCount: number;
  publishedExamCount: number;
}

export interface CategoryInput {
  name: string;
  slug: string;
  description: string;
  displayOrder: number;
}

export interface TopicAdmin {
  id: string;
  moduleId: string;
  title: string;
  displayOrder: number;
  status: ContentStatus;
  version: number;
  prerequisiteTopicIds: string[];
  publishedQuestionCount: number;
  trialQuestionCount: number;
}

export interface ModuleAdmin {
  id: string;
  subjectId: string;
  title: string;
  displayOrder: number;
  status: ContentStatus;
  version: number;
  topics: TopicAdmin[];
}

export interface SubjectAdmin {
  id: string;
  title: string;
  code: string | null;
  description: string | null;
  status: ContentStatus;
  version: number;
  linkedExamCount: number;
  modules: ModuleAdmin[];
}

export interface AdminExamFilters {
  q: string;
  status: ExamStatus | '';
  categoryId: string;
  page: number;
}

export const catalogKeys = {
  all: ['admin', 'catalog'] as const,
  exams: () => [...catalogKeys.all, 'exams'] as const,
  examList: (f: AdminExamFilters) => [...catalogKeys.exams(), 'list', f] as const,
  exam: (id: string) => [...catalogKeys.exams(), id] as const,
  categories: () => [...catalogKeys.all, 'categories'] as const,
  library: () => [...catalogKeys.all, 'library'] as const,
};

export function useAdminExams(filters: AdminExamFilters) {
  return useQuery({
    queryKey: catalogKeys.examList(filters),
    queryFn: ({ signal }) =>
      apiFetch<{ items: AdminExam[]; page: PageMeta }>('/admin/catalog/exams', {
        signal,
        query: {
          page: filters.page,
          size: 25,
          q: filters.q || undefined,
          status: filters.status || undefined,
          categoryId: filters.categoryId || undefined,
        },
      }),
    placeholderData: keepPreviousData,
  });
}

export function useAdminExam(id: string | undefined) {
  return useQuery({
    queryKey: catalogKeys.exam(id ?? 'new'),
    queryFn: ({ signal }) => apiFetch<AdminExamDetail>(`/admin/catalog/exams/${id ?? ''}`, { signal }),
    enabled: id !== undefined,
  });
}

export function useAdminCategories() {
  return useQuery({
    queryKey: catalogKeys.categories(),
    queryFn: ({ signal }) => apiFetch<AdminCategory[]>('/admin/catalog/categories', { signal }),
  });
}

export function useLibrary() {
  return useQuery({
    queryKey: catalogKeys.library(),
    queryFn: ({ signal }) => apiFetch<SubjectAdmin[]>('/admin/catalog/subjects', { signal }),
  });
}

const ifMatch = (version: number) => ({ 'If-Match': `"${version}"` });

export const createExam = (input: ExamInput) =>
  apiFetch<AdminExamDetail>('/admin/catalog/exams', { method: 'POST', json: input });

export const updateExam = (id: string, version: number, input: ExamInput) =>
  apiFetch<AdminExamDetail>(`/admin/catalog/exams/${id}`, { method: 'PUT', json: input, headers: ifMatch(version) });

export const publishExam = (id: string) =>
  apiFetch<AdminExamDetail>(`/admin/catalog/exams/${id}/publish`, { method: 'POST' });

export const archiveExam = (id: string) =>
  apiFetch<AdminExamDetail>(`/admin/catalog/exams/${id}/archive`, { method: 'POST' });

export const restoreExam = (id: string) =>
  apiFetch<AdminExamDetail>(`/admin/catalog/exams/${id}/restore`, { method: 'POST' });

/** Adds an existing subject ({ subjectId }) or a new one ({ title }) to the exam. */
export const linkSubject = (examId: string, input: { subjectId: string } | { title: string }) =>
  apiFetch<AdminExamDetail>(`/admin/catalog/exams/${examId}/subjects`, { method: 'POST', json: input });

export const unlinkSubject = (examId: string, subjectId: string) =>
  apiFetch<AdminExamDetail>(`/admin/catalog/exams/${examId}/subjects/${subjectId}`, { method: 'DELETE' });

export const createSampleExam = () =>
  apiFetch<AdminExamDetail>('/admin/catalog/sample-exams/ol-science', { method: 'POST' });

export const configureMode = (examId: string, mode: Mode, input: ModeInput) =>
  apiFetch<ModeSettings>(`/admin/catalog/exams/${examId}/modes/${mode}`, { method: 'PUT', json: input });

export const createCategory = (input: CategoryInput) =>
  apiFetch<AdminCategory>('/admin/catalog/categories', { method: 'POST', json: input });

export const updateCategory = (id: string, version: number, input: CategoryInput) =>
  apiFetch<AdminCategory>(`/admin/catalog/categories/${id}`, { method: 'PUT', json: input, headers: ifMatch(version) });

export const setCategoryArchived = (id: string, archived: boolean) =>
  apiFetch<AdminCategory>(`/admin/catalog/categories/${id}/${archived ? 'archive' : 'restore'}`, { method: 'POST' });

export interface SubjectInput {
  title: string;
  code: string;
  description: string;
  status?: ContentStatus;
}

export const createSubject = (input: SubjectInput) =>
  apiFetch<SubjectAdmin>('/admin/catalog/subjects', { method: 'POST', json: input });

export const updateSubject = (id: string, version: number, input: SubjectInput) =>
  apiFetch<SubjectAdmin>(`/admin/catalog/subjects/${id}`, { method: 'PUT', json: input, headers: ifMatch(version) });

export const createModule = (input: { subjectId: string; title: string; displayOrder: number }) =>
  apiFetch<ModuleAdmin>('/admin/catalog/modules', { method: 'POST', json: input });

export const updateModule = (
  id: string,
  version: number,
  input: { title: string; displayOrder: number; status?: ContentStatus },
) => apiFetch<ModuleAdmin>(`/admin/catalog/modules/${id}`, { method: 'PUT', json: input, headers: ifMatch(version) });

/** Give moduleId, or subjectId to add the topic straight under a subject (its first module, created if needed). */
export const createTopic = (
  input: ({ moduleId: string } | { subjectId: string }) & { title: string; displayOrder: number; prerequisiteTopicIds: string[] },
) =>
  apiFetch<TopicAdmin>('/admin/catalog/topics', { method: 'POST', json: input });

export const updateTopic = (
  id: string,
  version: number,
  input: { title: string; displayOrder: number; status?: ContentStatus; prerequisiteTopicIds?: string[] },
) => apiFetch<TopicAdmin>(`/admin/catalog/topics/${id}`, { method: 'PUT', json: input, headers: ifMatch(version) });

/**
 * Lower-case, hyphenated address made from a title ("IELTS Academic" → "ielts-academic"). Abbreviation marks are
 * dropped rather than turned into hyphens: "O/L" → "ol", "G.C.E." → "gce", "Writer's" → "writers".
 */
export function slugify(text: string): string {
  return text
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/(?<=[A-Za-z0-9])[./'’](?=[A-Za-z0-9])/g, '')
    .replace(/(?<=[A-Za-z0-9])\.(?=\s|$)/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80)
    .replace(/-+$/g, '');
}

export const statusLabels: Record<ExamStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
};
