import { createContext, useContext } from 'react';
import type { AuthState, Me, Role } from './types';

export interface AuthContextValue {
  state: AuthState;
  /** Sends the browser to Google; comes back to `returnTo` (default: the current page). */
  signIn: (returnTo?: string) => void;
  signOut: () => Promise<void>;
  /** Re-reads the profile, e.g. after the user edits it. */
  setUser: (user: Me) => void;
  hasRole: (role: Role) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>');
  return value;
}
