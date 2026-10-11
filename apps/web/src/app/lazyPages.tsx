import { lazy } from 'react';

// Signed-in areas load on demand, so first-time visitors download less.
export const AccountPage = lazy(() => import('@/features/account/AccountPage').then((m) => ({ default: m.AccountPage })));
export const AdminLayout = lazy(() => import('@/components/layout/AdminLayout').then((m) => ({ default: m.AdminLayout })));
export const UsersPage = lazy(() => import('@/features/admin/users/UsersPage').then((m) => ({ default: m.UsersPage })));
export const AdminExamsPage = lazy(() => import('@/features/admin/catalog/ExamsPage').then((m) => ({ default: m.ExamsPage })));
export const ExamEditorPage = lazy(() =>
  import('@/features/admin/catalog/ExamEditorPage').then((m) => ({ default: m.ExamEditorPage })),
);
export const CategoriesPage = lazy(() =>
  import('@/features/admin/catalog/CategoriesPage').then((m) => ({ default: m.CategoriesPage })),
);
export const ContentLibraryPage = lazy(() =>
  import('@/features/admin/catalog/ContentLibraryPage').then((m) => ({ default: m.ContentLibraryPage })),
);
