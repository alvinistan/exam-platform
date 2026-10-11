import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router';
import { ChevronDown, LayoutDashboard, LogOut, UserRound } from 'lucide-react';
import { Avatar, Button, Skeleton } from '@/components/ui';
import { useAuth } from '@/lib/auth/AuthContext';

/** Header control: "Sign in" when signed out; otherwise the user's name with account links and sign-out. */
export function AccountMenu() {
  const { state, signIn, signOut, hasRole } = useAuth();
  const location = useLocation();
  // Open state is tied to the page it was opened on, so navigating anywhere closes the menu.
  const [openOn, setOpenOn] = useState<string | null>(null);
  const open = openOn === location.pathname;
  const setOpen = (value: boolean | ((current: boolean) => boolean)) => {
    const next = typeof value === 'function' ? value(open) : value;
    setOpenOn(next ? location.pathname : null);
  };
  const panelId = useId();
  const container = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpenOn(null);
    const onClick = (e: MouseEvent) => {
      if (container.current && !container.current.contains(e.target as Node)) setOpenOn(null);
    };
    document.addEventListener('keydown', onKey);
    document.addEventListener('mousedown', onClick);
    return () => {
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  if (state.status === 'loading') {
    return (
      <>
        <span className="sr-only" role="status">
          Checking your sign-in…
        </span>
        <Skeleton className="h-9 w-28" />
      </>
    );
  }

  if (state.status === 'signedOut') {
    if (location.pathname === '/signin') return null;
    return (
      <Button size="sm" onClick={() => signIn()}>
        Sign in
      </Button>
    );
  }

  const { user } = state;
  const itemClass =
    'flex min-h-10 w-full items-center gap-2.5 rounded-sm px-3 py-2 text-left text-[14.5px] font-medium text-ink no-underline hover:bg-surface-2 hover:text-ink';

  return (
    <div ref={container} className="relative">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={`${user.displayName}, account menu`}
        onClick={() => setOpen((o) => !o)}
        className="flex min-h-11 items-center gap-2 rounded-md px-2 py-1.5 hover:bg-surface-2"
      >
        <Avatar name={user.displayName} photoUrl={user.photoUrl} />
        <span className="hidden max-w-40 truncate text-[15px] font-semibold sm:inline">{user.displayName}</span>
        <ChevronDown aria-hidden="true" className="size-4 text-muted" />
      </button>
      {open && (
        <div
          id={panelId}
          className="absolute top-full right-0 z-40 mt-2 w-64 rounded-md border border-border bg-surface p-1.5 shadow-raised"
        >
          <div className="border-b border-border px-3 pt-1.5 pb-2.5">
            <p className="truncate font-semibold">{user.displayName}</p>
            <p className="truncate text-[13px] text-muted">{user.email}</p>
          </div>
          <nav aria-label="Account" className="flex flex-col gap-0.5 pt-1.5">
            <Link to="/account" className={itemClass}>
              <UserRound aria-hidden="true" className="size-4 text-muted" /> Your account
            </Link>
            {hasRole('ADMIN') && (
              <Link to="/admin/users" className={itemClass}>
                <LayoutDashboard aria-hidden="true" className="size-4 text-muted" /> Administration
              </Link>
            )}
            <button type="button" className={itemClass} onClick={() => void signOut()}>
              <LogOut aria-hidden="true" className="size-4 text-muted" /> Sign out
            </button>
          </nav>
        </div>
      )}
    </div>
  );
}
