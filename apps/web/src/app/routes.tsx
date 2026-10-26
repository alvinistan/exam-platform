import { Navigate, type RouteObject } from 'react-router';
import { PublicLayout } from '@/components/layout/PublicLayout';
import {
  AccessPage,
  AccountPage,
  AdminExamsPage,
  AdminLayout,
  AttemptPage,
  CategoriesPage,
  CheckoutCancelPage,
  CheckoutPage,
  CheckoutReturnPage,
  ContentLibraryPage,
  DashboardPage,
  ExamSetupIndex,
  ExamSetupPage,
  NewExamPage,
  PlansPaymentsPage,
  ProgressPage,
  QuestionEditorPage,
  QuestionImportPage,
  QuestionsPage,
  StartPage,
  TestPaymentPage,
  UsersPage,
} from './lazyPages';
import { SignInPage } from '@/features/auth/SignInPage';
import { CataloguePage } from '@/features/exams/CataloguePage';
import { ExamPage } from '@/features/exams/ExamPage';
import { HomePage } from '@/features/home/HomePage';
import { PlansPage } from '@/features/billing/PlansPage';
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
      {
        path: 'exams/:slug/start/:mode',
        element: (
          <RequireAuth>
            <StartPage />
          </RequireAuth>
        ),
      },
      {
        path: 'dashboard',
        element: (
          <RequireAuth>
            <DashboardPage />
          </RequireAuth>
        ),
      },
      {
        path: 'progress',
        element: (
          <RequireAuth>
            <ProgressPage />
          </RequireAuth>
        ),
      },
      {
        path: 'attempts/:attemptId',
        element: (
          <RequireAuth>
            <AttemptPage />
          </RequireAuth>
        ),
      },
      { path: 'plans', element: <PlansPage /> },
      {
        path: 'checkout/:planId',
        element: (
          <RequireAuth>
            <CheckoutPage />
          </RequireAuth>
        ),
      },
      {
        path: 'checkout/test/:transactionId',
        element: (
          <RequireAuth>
            <TestPaymentPage />
          </RequireAuth>
        ),
      },
      {
        // PayHere sends the student back to these two; "return" doesn't mean the payment worked.
        path: 'checkout/return',
        element: (
          <RequireAuth>
            <CheckoutReturnPage />
          </RequireAuth>
        ),
      },
      { path: 'checkout/cancel', element: <CheckoutCancelPage /> },
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
          { path: 'exams/new', element: <NewExamPage /> },
          { path: 'exams/:examId', element: <ExamSetupIndex /> },
          { path: 'exams/:examId/:step', element: <ExamSetupPage /> },
          { path: 'categories', element: <CategoriesPage /> },
          { path: 'access', element: <AccessPage /> },
          { path: 'plans', element: <PlansPaymentsPage /> },
          { path: 'content', element: <ContentLibraryPage /> },
          { path: 'questions', element: <QuestionsPage /> },
          { path: 'questions/new', element: <QuestionEditorPage /> },
          { path: 'questions/import', element: <QuestionImportPage /> },
          { path: 'questions/:questionId', element: <QuestionEditorPage /> },
          { path: 'users', element: <UsersPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
