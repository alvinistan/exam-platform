import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import type { Me, Role } from '@/lib/auth/types';

export type AdminUser = Me;

export interface UserPage {
  items: AdminUser[];
  page: { page: number; size: number; totalElements: number; totalPages: number };
}

export interface UserFilters {
  q: string;
  role: Role | '';
  status: 'ACTIVE' | 'SUSPENDED' | '';
  page: number;
}

export const userKeys = {
  all: ['admin', 'users'] as const,
  list: (f: UserFilters) => [...userKeys.all, f] as const,
};

export function useUsers(filters: UserFilters) {
  return useQuery({
    queryKey: userKeys.list(filters),
    queryFn: ({ signal }) =>
      apiFetch<UserPage>('/admin/users', {
        signal,
        query: {
          page: filters.page,
          size: 25,
          q: filters.q || undefined,
          role: filters.role || undefined,
          status: filters.status || undefined,
        },
      }),
    placeholderData: keepPreviousData,
  });
}

export const setAdmin = (userId: string, admin: boolean) =>
  apiFetch<undefined>(`/admin/users/${userId}/roles/ADMIN`, { method: admin ? 'PUT' : 'DELETE' });

export const setStatus = (userId: string, status: 'ACTIVE' | 'SUSPENDED', reason: string) =>
  apiFetch<undefined>(`/admin/users/${userId}/status`, { method: 'PUT', json: { status, reason } });
