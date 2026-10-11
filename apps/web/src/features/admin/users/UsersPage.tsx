import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Search, UsersRound } from 'lucide-react';
import { z } from 'zod';
import { Alert, Avatar, Badge, Button, Dialog, EmptyState, ErrorState, SelectField, Skeleton, TextField } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthContext';
import { setAdmin, setStatus, useUsers, userKeys, type AdminUser, type UserFilters } from './api';

type PendingAction =
  | { kind: 'grant' | 'revoke'; user: AdminUser }
  | { kind: 'suspend' | 'reactivate'; user: AdminUser };

const reasonSchema = z.object({
  reason: z.string().trim().min(3, 'Give a short reason (at least 3 characters).').max(500, 'Use 500 characters or fewer.'),
});

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

export function UsersPage() {
  const { state } = useAuth();
  const myId = state.status === 'signedIn' ? state.user.id : null;
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [role, setRole] = useState<UserFilters['role']>('');
  const [status, setStatusFilter] = useState<UserFilters['status']>('');
  const q = useDebounced(search.trim(), 300);
  // The page number belongs to one set of filters: changing a filter starts again at page 1.
  const filterKey = JSON.stringify([q, role, status]);
  const [pageState, setPageState] = useState({ key: filterKey, page: 0 });
  const page = pageState.key === filterKey ? pageState.page : 0;
  const setPage = (update: (p: number) => number) => setPageState({ key: filterKey, page: update(page) });
  const filters: UserFilters = { q, role, status, page };
  const users = useUsers(filters);
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reasonForm = useForm<{ reason: string }>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: '' } });

  const action = useMutation({
    mutationFn: async ({ act, reason }: { act: PendingAction; reason?: string }) => {
      switch (act.kind) {
        case 'grant':
          return setAdmin(act.user.id, true);
        case 'revoke':
          return setAdmin(act.user.id, false);
        case 'suspend':
          return setStatus(act.user.id, 'SUSPENDED', reason ?? '');
        case 'reactivate':
          return setStatus(act.user.id, 'ACTIVE', reason ?? '');
      }
    },
    onSuccess: (_data, { act }) => {
      const messages = {
        grant: `${act.user.displayName} is now an administrator.`,
        revoke: `${act.user.displayName} is no longer an administrator.`,
        suspend: `${act.user.displayName} is suspended and signed out everywhere.`,
        reactivate: `${act.user.displayName} can sign in again.`,
      };
      setNotice(messages[act.kind]);
      close();
      void queryClient.invalidateQueries({ queryKey: userKeys.all });
    },
  });

  function open(act: PendingAction) {
    action.reset();
    reasonForm.reset({ reason: '' });
    setNotice(null);
    setPending(act);
  }

  function close() {
    setPending(null);
  }

  const needsReason = pending?.kind === 'suspend' || pending?.kind === 'reactivate';
  const total = users.data?.page.totalElements ?? 0;
  const from = total === 0 ? 0 : page * 25 + 1;
  const to = Math.min(total, (page + 1) * 25);

  return (
    <div className="flex flex-col gap-5">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Users</h1>
        <p className="text-muted">Everyone who has signed in. Give administrator access or suspend accounts.</p>
      </div>

      <div className="grid gap-3 sm:grid-cols-[1fr_180px_180px]">
        <div className="relative">
          <TextField
            label="Search users"
            placeholder="Name or email"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-10"
            maxLength={100}
          />
          <Search aria-hidden="true" className="pointer-events-none absolute bottom-3.5 left-3 size-4 text-muted" />
        </div>
        <SelectField label="Role" value={role} onChange={(e) => setRole(e.target.value as UserFilters['role'])}>
          <option value="">All roles</option>
          <option value="ADMIN">Administrators</option>
          <option value="STUDENT">Students</option>
        </SelectField>
        <SelectField label="Status" value={status} onChange={(e) => setStatusFilter(e.target.value as UserFilters['status'])}>
          <option value="">Any status</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
        </SelectField>
      </div>

      {notice && <Alert tone="success" title={notice} />}

      {users.isPending ? (
        <div className="flex flex-col gap-2" aria-busy="true">
          <p role="status" className="sr-only">
            Loading users…
          </p>
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-14 w-full" />
          ))}
        </div>
      ) : users.isError ? (
        <ErrorState
          title="We couldn’t load users"
          onRetry={() => void users.refetch()}
          correlationId={users.error instanceof ApiError ? users.error.correlationId : undefined}
        />
      ) : users.data.items.length === 0 ? (
        <EmptyState icon={UsersRound} title="No users match">
          <p>Try a different name, email or filter.</p>
        </EmptyState>
      ) : (
        <>
          <div className="relative overflow-x-auto rounded-lg border border-border bg-surface">
            <table className="w-full border-collapse text-[14.5px]">
              <caption className="sr-only">Users, {total} in total</caption>
              <thead>
                <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                  <th scope="col" className="px-4 py-3">Person</th>
                  <th scope="col" className="px-4 py-3">Access</th>
                  <th scope="col" className="px-4 py-3">Status</th>
                  <th scope="col" className="px-4 py-3 whitespace-nowrap">Last sign-in</th>
                  <th scope="col" className="px-4 py-3"><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {users.data.items.map((u) => {
                  const isAdmin = u.roles.includes('ADMIN');
                  const isMe = u.id === myId;
                  return (
                    <tr key={u.id} className="border-b border-border last:border-b-0">
                      <td className="px-4 py-3">
                        <div className="flex min-w-0 items-center gap-3">
                          <Avatar name={u.displayName} photoUrl={u.photoUrl} />
                          <div className="min-w-0">
                            <p className="truncate font-semibold">
                              {u.displayName} {isMe && <span className="font-normal text-muted">(you)</span>}
                            </p>
                            <p className="truncate text-[13px] text-muted">{u.email}</p>
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={isAdmin ? 'primary' : 'neutral'}>{isAdmin ? 'Administrator' : 'Student'}</Badge>
                      </td>
                      <td className="px-4 py-3">
                        <Badge tone={u.status === 'ACTIVE' ? 'free' : 'danger'}>
                          {u.status === 'ACTIVE' ? 'Active' : 'Suspended'}
                        </Badge>
                      </td>
                      <td className="px-4 py-3 whitespace-nowrap text-muted">
                        {u.lastLoginAt ? dateFormat.format(new Date(u.lastLoginAt)) : 'Never'}
                      </td>
                      <td className="px-4 py-3">
                        {!isMe && (
                          <div className="flex justify-end gap-2">
                            <Button
                              size="sm"
                              variant="secondary"
                              aria-label={`${isAdmin ? 'Remove admin' : 'Make admin'} for ${u.displayName}`}
                              onClick={() => open({ kind: isAdmin ? 'revoke' : 'grant', user: u })}
                            >
                              {isAdmin ? 'Remove admin' : 'Make admin'}
                            </Button>
                            <Button
                              size="sm"
                              variant={u.status === 'ACTIVE' ? 'ghost' : 'secondary'}
                              aria-label={`${u.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'} ${u.displayName}`}
                              onClick={() => open({ kind: u.status === 'ACTIVE' ? 'suspend' : 'reactivate', user: u })}
                            >
                              {u.status === 'ACTIVE' ? 'Suspend' : 'Reactivate'}
                            </Button>
                          </div>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted" aria-live="polite">
              Showing {from}–{to} of {total}
            </p>
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={page + 1 >= (users.data.page.totalPages || 1)}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        </>
      )}

      <Dialog
        open={pending !== null}
        onClose={close}
        title={
          pending?.kind === 'grant'
            ? `Make ${pending.user.displayName} an administrator?`
            : pending?.kind === 'revoke'
              ? `Remove ${pending.user.displayName}’s administrator access?`
              : pending?.kind === 'suspend'
                ? `Suspend ${pending.user.displayName}?`
                : `Reactivate ${pending?.user.displayName ?? ''}?`
        }
        description={
          pending?.kind === 'grant'
            ? 'Administrators can manage exams, questions, plans and every user account.'
            : pending?.kind === 'revoke'
              ? 'They keep their student account. The change applies within 15 minutes.'
              : pending?.kind === 'suspend'
                ? 'They are signed out on every device and can’t sign in until reactivated. Their data is kept.'
                : 'They will be able to sign in again.'
        }
      >
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={
            needsReason
              ? reasonForm.handleSubmit(({ reason }) => pending && action.mutate({ act: pending, reason }))
              : (e) => {
                  e.preventDefault();
                  if (pending) action.mutate({ act: pending });
                }
          }
        >
          {needsReason && (
            <TextField
              label="Reason"
              help="Recorded in the audit log."
              error={reasonForm.formState.errors.reason?.message}
              {...reasonForm.register('reason')}
            />
          )}
          {action.isError && (
            <Alert tone="error" title="That didn’t work">
              {action.error instanceof ApiError ? action.error.message : 'Please try again.'}
            </Alert>
          )}
          <div className="flex flex-wrap justify-end gap-3">
            <Button variant="secondary" onClick={close}>
              Cancel
            </Button>
            <Button type="submit" variant={pending?.kind === 'suspend' ? 'danger' : 'primary'} loading={action.isPending}>
              {pending?.kind === 'grant'
                ? 'Make admin'
                : pending?.kind === 'revoke'
                  ? 'Remove admin'
                  : pending?.kind === 'suspend'
                    ? 'Suspend'
                    : 'Reactivate'}
            </Button>
          </div>
        </form>
      </Dialog>
    </div>
  );
}
