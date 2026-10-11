import { Suspense } from 'react';
import { Outlet } from 'react-router';
import { AccountMenu } from './AccountMenu';
import { Brand } from './Brand';
import { PageLoading } from './PageLoading';

export function PublicLayout() {
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
          <Brand />
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
