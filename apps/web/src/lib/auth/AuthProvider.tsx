import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api/client';
import { AuthContext, type AuthContextValue } from './AuthContext';
import { msUntilRenewal, onSessionChange, refreshAccessToken, signOutOnServer, startSignIn } from './session';
import type { AuthState, Me, Role } from './types';

/**
 * Restores the session on page load (refresh cookie → access token → profile), renews the access token a minute
 * before it expires, and exposes sign-in/sign-out to the app.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null });

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const token = await refreshAccessToken();
      let next: AuthState = { status: 'signedOut', user: null };
      if (token) {
        try {
          next = { status: 'signedIn', user: await apiFetch<Me>('/users/me') };
        } catch {
          // Profile unavailable: treat as signed out; the next action can sign in again.
        }
      }
      if (!cancelled) setState(next);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // The server ended the session (signed out elsewhere, suspended): show it straight away.
  useEffect(
    () =>
      onSessionChange(() => {
        setState((s) => (s.status === 'signedIn' ? { status: 'signedOut', user: null } : s));
      }),
    [],
  );

  // Renew the access token shortly before it expires, so requests never wait on a refresh.
  useEffect(() => {
    if (state.status !== 'signedIn') return;
    const delay = msUntilRenewal();
    if (delay === null) return;
    const timer = window.setTimeout(() => void refreshAccessToken(), delay);
    return () => window.clearTimeout(timer);
  }, [state]);

  const signOut = useCallback(async () => {
    await signOutOnServer();
    queryClient.clear();
    setState({ status: 'signedOut', user: null, endedByUser: true });
  }, [queryClient]);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      signIn: (returnTo) => startSignIn(returnTo ?? window.location.pathname + window.location.search),
      signOut,
      setUser: (user: Me) => setState({ status: 'signedIn', user }),
      hasRole: (role: Role) => state.status === 'signedIn' && state.user.roles.includes(role),
    }),
    [state, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
