import { lazy } from 'react';

// Signed-in areas load on demand, so first-time visitors download less.
export const AccountPage = lazy(() => import('@/features/account/AccountPage').then((m) => ({ default: m.AccountPage })));
export const AdminLayout = lazy(() => import('@/components/layout/AdminLayout').then((m) => ({ default: m.AdminLayout })));
export const UsersPage = lazy(() => import('@/features/admin/users/UsersPage').then((m) => ({ default: m.UsersPage })));
