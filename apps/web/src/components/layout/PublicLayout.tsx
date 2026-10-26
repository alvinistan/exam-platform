import { Suspense } from 'react';
import { NavLink, Outlet } from 'react-router';
import { useAuth } from '@/lib/auth/AuthContext';
import { AccountMenu } from './AccountMenu';
import { Brand } from './Brand';
import { PageLoading } from './PageLoading';

const navClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-sm px-2 py-2 text-[15px] font-semibold no-underline ${isActive ? 'text-primary' : 'text-ink-2 hover:text-ink'}`;

export function PublicLayout() {
  const { state } = useAuth();
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-surface px-4 py-2 font-semibold focus:not-sr-only focus:fixed focus:left-4 focus:top-4"
      >
        Skip to content
      </a>
      <header className="border-b border-border bg-surface">
        <div className="mx-auto flex min-h-[68px] max-w-[1200px] items-center justify-between gap-4 px-4 sm:px-8">
          <div className="flex min-w-0 items-center gap-3 sm:gap-6">
            <Brand />
            <nav aria-label="Main" className="flex items-center gap-0 sm:gap-3">
              {state.status === 'signedIn' && (
                <NavLink to="/dashboard" className={navClass}>
                  Dashboard
                </NavLink>
              )}
              <NavLink to="/exams" className={navClass}>
                Exams
              </NavLink>
              {/* Phones: Premium is reached from the account menu and locked exams, to keep the header on one line. */}
              <NavLink to="/plans" className={(s) => `${navClass(s)} hidden sm:inline`}>
                Pricing
              </NavLink>
            </nav>
          </div>
          <AccountMenu />
        </div>
      </header>
      <main id="main" tabIndex={-1} className="flex-1 focus:shadow-none">
        <Suspense fallback={<PageLoading />}>
          <Outlet />
        </Suspense>
      </main>
      <footer className="border-t border-border bg-surface">
        <div className="mx-auto max-w-[1200px] px-4 py-6 text-sm text-muted sm:px-8">
          © {new Date().getFullYear()} Leenex Labs · From potential to power
        </div>
      </footer>
    </div>
  );
}
