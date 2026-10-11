import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';

export type Mode = 'PRACTICE' | 'MOCK' | 'USER_FRIENDLY';

/** Public exam fields from catalog-service (contracts/openapi/catalog-service.yaml → ExamSummary). */
export interface ExamSummary {
  id: string;
  title: string;
  slug: string;
  categoryId: string;
  shortDescription?: string;
  coverUrl?: string | null;
  modes: Mode[];
}

export const examKeys = {
  bySlug: (slug: string) => ['exams', 'by-slug', slug] as const,
};

export function useExamBySlug(slug: string) {
  return useQuery({
    queryKey: examKeys.bySlug(slug),
    queryFn: ({ signal }) => apiFetch<ExamSummary>(`/exams/by-slug/${encodeURIComponent(slug)}`, { signal }),
  });
}
