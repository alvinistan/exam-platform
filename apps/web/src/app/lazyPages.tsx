import { lazy } from 'react';

// Signed-in areas load on demand, so first-time visitors download less.
export const AccountPage = lazy(() => import('@/features/account/AccountPage').then((m) => ({ default: m.AccountPage })));
export const AdminLayout = lazy(() => import('@/components/layout/AdminLayout').then((m) => ({ default: m.AdminLayout })));
export const UsersPage = lazy(() => import('@/features/admin/users/UsersPage').then((m) => ({ default: m.UsersPage })));
export const AdminExamsPage = lazy(() => import('@/features/admin/catalog/ExamsPage').then((m) => ({ default: m.ExamsPage })));
export const NewExamPage = lazy(() => import('@/features/admin/setup/ExamSetupPage').then((m) => ({ default: m.NewExamPage })));
export const ExamSetupIndex = lazy(() => import('@/features/admin/setup/ExamSetupPage').then((m) => ({ default: m.ExamSetupIndex })));
export const ExamSetupPage = lazy(() => import('@/features/admin/setup/ExamSetupPage').then((m) => ({ default: m.ExamSetupPage })));
export const AccessPage = lazy(() => import('@/features/admin/access/AccessPage').then((m) => ({ default: m.AccessPage })));
export const CategoriesPage = lazy(() =>
  import('@/features/admin/catalog/CategoriesPage').then((m) => ({ default: m.CategoriesPage })),
);
export const ContentLibraryPage = lazy(() =>
  import('@/features/admin/catalog/ContentLibraryPage').then((m) => ({ default: m.ContentLibraryPage })),
);
export const QuestionsPage = lazy(() => import('@/features/admin/questions/QuestionsPage').then((m) => ({ default: m.QuestionsPage })));
export const QuestionEditorPage = lazy(() =>
  import('@/features/admin/questions/QuestionEditorPage').then((m) => ({ default: m.QuestionEditorPage })),
);
export const QuestionImportPage = lazy(() => import('@/features/admin/questions/ImportPage').then((m) => ({ default: m.ImportPage })));
export const StartPage = lazy(() => import('@/features/attempts/StartPage').then((m) => ({ default: m.StartPage })));
export const AttemptPage = lazy(() => import('@/features/attempts/AttemptPage').then((m) => ({ default: m.AttemptPage })));
export const ProgressPage = lazy(() => import('@/features/progress/ProgressPage').then((m) => ({ default: m.ProgressPage })));
export const PlansPaymentsPage = lazy(() =>
  import('@/features/admin/billing/PlansPaymentsPage').then((m) => ({ default: m.PlansPaymentsPage })),
);
export const FinancePage = lazy(() =>
  import('@/features/admin/finance/FinancePage').then((m) => ({ default: m.FinancePage })),
);
export const PaymentsPage = lazy(() =>
  import('@/features/admin/finance/PaymentsPage').then((m) => ({ default: m.PaymentsPage })),
);
export const PaymentHistoryPage = lazy(() =>
  import('@/features/account/PaymentHistoryPage').then((m) => ({ default: m.PaymentHistoryPage })),
);
export const ReceiptPage = lazy(() => import('@/features/account/ReceiptPage').then((m) => ({ default: m.ReceiptPage })));
export const PromotionsPage = lazy(() =>
  import('@/features/admin/billing/PromotionsPage').then((m) => ({ default: m.PromotionsPage })),
);
export const CheckoutPage = lazy(() => import('@/features/billing/CheckoutPage').then((m) => ({ default: m.CheckoutPage })));
export const TestPaymentPage = lazy(() => import('@/features/billing/TestPaymentPage').then((m) => ({ default: m.TestPaymentPage })));
export const CheckoutReturnPage = lazy(() =>
  import('@/features/billing/CheckoutResultPage').then((m) => ({ default: m.CheckoutReturnPage })),
);
export const CheckoutCancelPage = lazy(() =>
  import('@/features/billing/CheckoutResultPage').then((m) => ({ default: m.CheckoutCancelPage })),
);
export const DashboardPage = lazy(() => import('@/features/dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage })));
