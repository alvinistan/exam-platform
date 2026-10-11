/**
 * The access token lives only in memory (never localStorage), so a script injected into the page can't read a
 * long-lived credential. The refresh token is an HttpOnly cookie the browser sends to /api/auth/token only.
 */

interface TokenState {
  accessToken: string;
  expiresAt: number;
}

let current: TokenState | null = null;
let inFlight: Promise<string | null> | null = null;
const listeners = new Set<() => void>();

export function getAccessToken(): string | null {
  if (!current) return null;
  return Date.now() < current.expiresAt ? current.accessToken : null;
}

/** Milliseconds until the token should be renewed (one minute before it expires), or null when signed out. */
export function msUntilRenewal(): number | null {
  if (!current) return null;
  return Math.max(0, current.expiresAt - Date.now() - 60_000);
}

export function clearSession(): void {
  current = null;
  notify();
}

/** Called when the session ends unexpectedly (refresh refused), so the UI can show "signed out". */
export function onSessionChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function notify() {
  listeners.forEach((l) => l());
}

interface TokenResponse {
  accessToken: string;
  expiresIn: number;
}

/**
 * Gets a new access token using the refresh cookie. Concurrent callers share one request, so a page that fires
 * several API calls at once refreshes only once. Resolves to null when the user isn't signed in.
 */
export function refreshAccessToken(): Promise<string | null> {
  inFlight ??= (async () => {
    try {
      const response = await fetch('/api/auth/token', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'fetch', Accept: 'application/json' },
      });
      if (!response.ok) {
        const wasSignedIn = current !== null;
        current = null;
        if (wasSignedIn) notify();
        return null;
      }
      const body = (await response.json()) as TokenResponse;
      current = { accessToken: body.accessToken, expiresAt: Date.now() + body.expiresIn * 1000 };
      return body.accessToken;
    } catch {
      // Network trouble: keep the current state; callers show their own error.
      return null;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

/** Ends the session on the server (revokes the refresh token) and locally. */
export async function signOutOnServer(): Promise<void> {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-Requested-With': 'fetch' },
    });
  } finally {
    clearSession();
  }
}

/** Full-page navigation to Google; returns to the given path afterwards. */
export function startSignIn(returnTo: string): void {
  window.location.assign(`/api/auth/google/start?returnTo=${encodeURIComponent(returnTo)}`);
}
