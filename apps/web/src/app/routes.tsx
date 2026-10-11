import { Navigate, type RouteObject } from 'react-router';
import { PublicLayout } from '@/components/layout/PublicLayout';
import { AccountPage, AdminLayout, UsersPage } from './lazyPages';
import { SignInPage } from '@/features/auth/SignInPage';
import { ExamPage } from '@/features/exams/ExamPage';
import { HomePage } from '@/features/home/HomePage';
import { NotFoundPage } from '@/features/NotFoundPage';
import { RequireAuth, RequireRole } from '@/lib/auth/guards';

/**
 * Route table. Exams use one dynamic route; there is never a page per exam.
 * Guards only shape the experience; the API enforces sign-in and roles on every request.
 */
export const routes: RouteObject[] = [
  {
    element: <PublicLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'exams/:slug', element: <ExamPage /> },
      { path: 'signin', element: <SignInPage /> },
      {
        path: 'account',
        element: (
          <RequireAuth>
            <AccountPage />
          </RequireAuth>
        ),
      },
      {
        path: 'admin',
        element: (
          <RequireRole role="ADMIN">
            <AdminLayout />
          </RequireRole>
        ),
        children: [
          { index: true, element: <Navigate to="users" replace /> },
          { path: 'users', element: <UsersPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
