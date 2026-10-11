import type { RouteObject } from 'react-router';
import { PublicLayout } from '@/components/layout/PublicLayout';
import { ExamPage } from '@/features/exams/ExamPage';
import { HomePage } from '@/features/home/HomePage';
import { NotFoundPage } from '@/features/NotFoundPage';

/**
 * Route table. Exams use one dynamic route; there is never a page per exam.
 * Student, admin and auth routes are added in their phases.
 */
export const routes: RouteObject[] = [
  {
    element: <PublicLayout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'exams/:slug', element: <ExamPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
