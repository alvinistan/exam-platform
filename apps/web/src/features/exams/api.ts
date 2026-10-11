import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';

export type Mode = 'PRACTICE' | 'MOCK' | 'USER_FRIENDLY';

export const MODES: readonly Mode[] = ['PRACTICE', 'MOCK', 'USER_FRIENDLY'];

export const modeLabels: Record<Mode, string> = {
  PRACTICE: 'Practice',
  MOCK: 'Mock exam',
  USER_FRIENDLY: 'Guided practice',
};

export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Public exam fields from catalog-service (contracts/openapi/catalog-service.yaml → ExamSummary). */
export interface ExamSummary {
  id: string;
  title: string;
  slug: string;
  categoryId: string;
  shortDescription?: string;
  coverUrl?: string | null;
  featured?: boolean;
  displayOrder?: number | null;
  modes: Mode[];
}

export interface Category {
  id: string;
  name: string;
  slug: string;
  description?: string | null;
  displayOrder?: number;
  /** Published exams in the category. */
  examCount?: number;
}

export interface TopicNode {
  id: string;
  title: string;
}

export interface SubjectNode {
  id: string;
  title: string;
  modules: { id: string; title: string; topics: TopicNode[] }[];
}

/** The part of a mode's settings students see. */
export interface PublicModeSettings {
  mode: Mode;
  enabled: boolean;
  defaultCount: number;
  minCount: number;
  maxCount: number;
  timeLimitSeconds?: number | null;
  perQuestionSeconds?: number | null;
  feedbackTiming: 'IMMEDIATE' | 'AFTER_SUBMIT';
  negativeMark: number;
  passMarkPct?: number | null;
  allowTopicChoice: boolean;
  maxRetakes?: number | null;
}

export interface ExamDetail extends ExamSummary {
  category: { id: string; name: string; slug: string };
  instructions: string;
  seo: { title?: string; description?: string };
  subjects: SubjectNode[];
  modeSettings: PublicModeSettings[];
}

export interface ExamPage {
  items: ExamSummary[];
  page: PageMeta;
}

export interface ExamFilters {
  q?: string;
  category?: string;
  featured?: boolean;
  page?: number;
  size?: 10 | 25 | 50 | 100;
}

export const examKeys = {
  all: ['exams'] as const,
  list: (f: ExamFilters) => [...examKeys.all, 'list', f] as const,
  bySlug: (slug: string) => [...examKeys.all, 'by-slug', slug] as const,
  categories: ['categories'] as const,
};

export function useExamBySlug(slug: string) {
  return useQuery({
    queryKey: examKeys.bySlug(slug),
    queryFn: ({ signal }) => apiFetch<ExamDetail>(`/exams/by-slug/${encodeURIComponent(slug)}`, { signal }),
  });
}

export function useExams(filters: ExamFilters) {
  return useQuery({
    queryKey: examKeys.list(filters),
    queryFn: ({ signal }) =>
      apiFetch<ExamPage>('/exams', {
        signal,
        query: {
          q: filters.q || undefined,
          category: filters.category || undefined,
          featured: filters.featured,
          page: filters.page ?? 0,
          size: filters.size ?? 25,
        },
      }),
    placeholderData: keepPreviousData,
  });
}

export function useCategories() {
  return useQuery({
    queryKey: examKeys.categories,
    queryFn: ({ signal }) => apiFetch<Category[]>('/categories', { signal }),
    staleTime: 5 * 60_000,
  });
}
