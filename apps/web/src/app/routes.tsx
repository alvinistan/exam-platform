import { Navigate, type RouteObject } from 'react-router';
import { PublicLayout } from '@/components/layout/PublicLayout';
import {
  AccountPage,
  AdminExamsPage,
  AdminLayout,
  CategoriesPage,
  ContentLibraryPage,
  ExamEditorPage,
  UsersPage,
} from './lazyPages';
import { SignInPage } from '@/features/auth/SignInPage';
import { CataloguePage } from '@/features/exams/CataloguePage';
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
      { path: 'exams', element: <CataloguePage /> },
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
          { index: true, element: <Navigate to="exams" replace /> },
          { path: 'exams', element: <AdminExamsPage /> },
          { path: 'exams/new', element: <ExamEditorPage /> },
          { path: 'exams/:examId', element: <ExamEditorPage /> },
          { path: 'categories', element: <CategoriesPage /> },
          { path: 'content', element: <ContentLibraryPage /> },
          { path: 'users', element: <UsersPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
