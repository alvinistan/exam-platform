import { apiFetch } from '@/lib/api/client';
import type { Me } from '@/lib/auth/types';

export function updateProfile(version: number, changes: { displayName: string }): Promise<Me> {
  return apiFetch<Me>('/users/me', { method: 'PATCH', json: changes, headers: { 'If-Match': `"${version}"` } });
}

export function deleteAccount(): Promise<undefined> {
  return apiFetch<undefined>('/users/me', { method: 'DELETE' });
}
