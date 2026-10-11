import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { ShieldAlert } from 'lucide-react';
import { EmptyState } from '@/components/ui';
import { useAuth } from './AuthContext';
import type { Role } from './types';

function Checking() {
  return (
    <p role="status" className="px-4 py-16 text-center text-muted">
      Checking your sign-in…
    </p>
  );
}

/** Shows the page only to signed-in users; others go to sign-in and come back here afterwards. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const location = useLocation();
  if (state.status === 'loading') return <Checking />;
  if (state.status === 'signedOut') {
    // Signing out on a members-only page goes home, not to the sign-in page.
    if (state.endedByUser) return <Navigate to="/" replace />;
    const returnTo = location.pathname + location.search;
    return <Navigate to={`/signin?returnTo=${encodeURIComponent(returnTo)}`} replace />;
  }
  return <>{children}</>;
}

/**
 * Hides a page from users without the role. This is only for a clear experience: the server enforces roles on
 * every request regardless.
 */
export function RequireRole({ role, children }: { role: Role; children: ReactNode }) {
  const { state, hasRole } = useAuth();
  if (state.status !== 'signedIn') return <RequireAuth>{children}</RequireAuth>;
  if (!hasRole(role)) {
    return (
      <EmptyState headingLevel="h1" icon={ShieldAlert} title="You don't have access to this page">
        <p>Ask an administrator if you think you should.</p>
      </EmptyState>
    );
  }
  return <>{children}</>;
}
