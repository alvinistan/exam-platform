import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Badge, Button, Card, Dialog, EmptyState, ErrorState, SelectField, Skeleton, TextField } from '@/components/ui';
import { accessKeys } from '@/features/access/api';
import { MODES, modeLabels } from '@/features/exams/api';
import { useUserNames } from '@/features/admin/questions/api';
import { useUsers } from '@/features/admin/users/api';
import { ApiError } from '@/lib/api/problem';
import { accessAdminKeys, grantEntitlement, revokeEntitlement, useEntitlements, useFreeAttempts, type Entitlement } from './api';

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

function useInvalidateAccess() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: accessAdminKeys.all });
    void queryClient.invalidateQueries({ queryKey: accessKeys.all });
  };
}

const errorText = (error: unknown) =>
  error instanceof ApiError ? (error.problem?.errors?.[0]?.message ?? error.message) : 'Please try again.';

/**
 * Free attempts per exam type across the catalogue (set in each exam's step 4), and Premium access granted by hand.
 * Students get the free attempts of each exam type; after that the type is locked until they have Premium.
 */
export function AccessPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Access</h1>
        <p className="text-muted">
          Every exam type gives each student a number of free attempts. When they are used up, that exam type needs Premium.
          Past results always stay.
        </p>
      </div>
      <FreeAttemptsCard />
      <PremiumAccessCard />
    </div>
  );
}

function FreeAttemptsCard() {
  const list = useFreeAttempts();
  return (
    <Card className="flex flex-col gap-4 p-6">
      <div>
        <h2 className="text-lg font-bold">Free attempts on published exams</h2>
        <p className="text-[14.5px] text-muted">
          Change the numbers in an exam’s setup, step 4 (Exam types). Changes apply to the next attempt a student starts.
        </p>
      </div>
      {list.isPending ? (
        <Skeleton className="h-32 w-full" />
      ) : list.isError ? (
        <ErrorState title="We couldn’t load the published exams" onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <p className="text-muted">No published exams yet. They appear here a few seconds after you publish them.</p>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[560px] border-collapse text-[14.5px]">
            <caption className="sr-only">Published exams and their free attempts</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">
                  Exam
                </th>
                {MODES.map((m) => (
                  <th key={m} scope="col" className="px-4 py-3">
                    {modeLabels[m]}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {list.data.map((e) => (
                <tr key={e.examId} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-3">
                    <Link to={`/admin/exams/${e.examId}/modes`} className="font-semibold">
                      {e.title || 'Untitled exam'}
                    </Link>
                  </td>
                  {MODES.map((m) => (
                    <td key={m} className="px-4 py-3 tabular-nums">
                      {e.modes.includes(m) ? (
                        e.freeAttempts[m] ? (
                          <Badge tone="free">{e.freeAttempts[m]} free</Badge>
                        ) : (
                          <Badge tone="premium">Premium only</Badge>
                        )
                      ) : (
                        <span className="text-muted">Off</span>
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

const stateTone = { ACTIVE: 'free', SCHEDULED: 'primary', EXPIRED: 'neutral', REVOKED: 'danger' } as const;
const stateLabel = { ACTIVE: 'Active', SCHEDULED: 'Starts later', EXPIRED: 'Expired', REVOKED: 'Revoked' } as const;

function PremiumAccessCard() {
  const entitlements = useEntitlements();
  const names = useUserNames([...(entitlements.data ?? []).flatMap((e) => [e.studentId, e.grantedBy ?? ''])].filter(Boolean));
  const [revoking, setRevoking] = useState<Entitlement | null>(null);
  const name = (id: string | null) => (id ? (names.data?.get(id) ?? 'Unknown user') : '—');
  return (
    <Card className="flex flex-col gap-5 p-6">
      <div>
        <h2 className="text-lg font-bold">Premium access for students</h2>
        <p className="text-[14.5px] text-muted">
          Give a student Premium by hand, for example for a trial or a school. Paid plans arrive in a later phase.
        </p>
      </div>
      <GrantForm />
      {entitlements.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : entitlements.isError ? (
        <ErrorState title="We couldn’t load Premium access" onRetry={() => void entitlements.refetch()} />
      ) : entitlements.data.length === 0 ? (
        <EmptyState title="Nobody has Premium yet">
          <p>Students you give Premium to appear here.</p>
        </EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[640px] border-collapse text-[14.5px]">
            <caption className="sr-only">Premium access</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">
                  Student
                </th>
                <th scope="col" className="px-4 py-3">
                  Status
                </th>
                <th scope="col" className="px-4 py-3">
                  Until
                </th>
                <th scope="col" className="px-4 py-3">
                  Reason
                </th>
                <th scope="col" className="px-4 py-3">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {entitlements.data.map((e) => (
                <tr key={e.id} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-3 font-semibold">{name(e.studentId)}</td>
                  <td className="px-4 py-3">
                    <Badge tone={stateTone[e.state]}>{stateLabel[e.state]}</Badge>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">{dateFormat.format(new Date(e.endsAt))}</td>
                  <td className="px-4 py-3 text-ink-2">
                    {e.revokedReason
                      ? `Revoked: ${e.revokedReason}`
                      : (e.grantReason ?? (e.source === 'SUBSCRIPTION' ? 'Paid plan' : '—'))}
                    <span className="block text-[13px] text-muted">
                      By {e.source === 'SUBSCRIPTION' ? 'payment' : name(e.grantedBy)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    {(e.state === 'ACTIVE' || e.state === 'SCHEDULED') && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setRevoking(e)}
                        aria-label={`Revoke Premium for ${name(e.studentId)}`}
                      >
                        Revoke
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <RevokeDialog
        entitlement={revoking}
        studentName={revoking ? name(revoking.studentId) : ''}
        onClose={() => setRevoking(null)}
      />
    </Card>
  );
}

function useDebounced<T>(value: T, ms: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(value), ms);
    return () => window.clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

const inDays = (days: number) => {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
};

function GrantForm() {
  const invalidate = useInvalidateAccess();
  const [search, setSearch] = useState('');
  const q = useDebounced(search.trim(), 300);
  const users = useUsers({ q, role: '', status: 'ACTIVE', page: 0 });
  const [studentId, setStudentId] = useState('');
  const [until, setUntil] = useState(inDays(30));
  const [reason, setReason] = useState('');
  const [problem, setProblem] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const grant = useMutation({
    // The end of the chosen day, in the administrator's time zone.
    mutationFn: () => grantEntitlement(studentId, new Date(`${until}T23:59:59`).toISOString(), reason.trim()),
    onSuccess: () => {
      const who = users.data?.items.find((u) => u.id === studentId)?.displayName ?? 'The student';
      setDone(`${who} has Premium until ${dateFormat.format(new Date(`${until}T12:00:00`))}.`);
      setReason('');
      setStudentId('');
      invalidate();
    },
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setDone(null);
    if (!studentId) return setProblem('Choose a student.');
    if (!until || until <= inDays(0)) return setProblem('Choose an end date after today.');
    if (reason.trim().length < 3) return setProblem('Say why, in a few words (at least 3 characters).');
    setProblem(null);
    grant.mutate();
  };
  return (
    <form
      noValidate
      onSubmit={submit}
      aria-label="Give Premium access"
      className="flex flex-col gap-4 rounded-md border border-border p-4"
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Find a student"
          placeholder="Name or email"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          maxLength={100}
        />
        <SelectField label="Student" value={studentId} onChange={(e) => setStudentId(e.target.value)}>
          <option value="">{users.isPending ? 'Loading…' : `Choose (${users.data?.items.length ?? 0} found)`}</option>
          {users.data?.items.map((u) => (
            <option key={u.id} value={u.id}>
              {u.displayName} — {u.email}
            </option>
          ))}
        </SelectField>
        <TextField label="Premium until" type="date" min={inDays(1)} value={until} onChange={(e) => setUntil(e.target.value)} />
        <TextField
          label="Reason"
          placeholder="For example: school trial"
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
        />
      </div>
      {(problem ?? (grant.isError ? errorText(grant.error) : null)) && (
        <p role="alert" className="text-[14px] text-danger-ink">
          {problem ?? errorText(grant.error)}
        </p>
      )}
      {done && <Alert tone="success" title={done} />}
      <div>
        <Button type="submit" variant="premium" loading={grant.isPending}>
          Give Premium
        </Button>
      </div>
    </form>
  );
}

function RevokeDialog({
  entitlement,
  studentName,
  onClose,
}: {
  entitlement: Entitlement | null;
  studentName: string;
  onClose: () => void;
}) {
  const invalidate = useInvalidateAccess();
  const [reason, setReason] = useState('');
  const revoke = useMutation({
    mutationFn: () => revokeEntitlement((entitlement as Entitlement).id, reason.trim()),
    onSuccess: () => {
      invalidate();
      setReason('');
      onClose();
    },
  });
  return (
    <Dialog
      open={entitlement !== null}
      onClose={onClose}
      title={`Revoke Premium for ${studentName}?`}
      description="Premium stops on their next request. Their results and history are kept."
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" loading={revoke.isPending} disabled={reason.trim().length < 3} onClick={() => revoke.mutate()}>
            Revoke
          </Button>
        </>
      }
    >
      <TextField
        label="Reason"
        placeholder="For example: refunded"
        value={reason}
        maxLength={500}
        onChange={(e) => setReason(e.target.value)}
      />
      {revoke.isError && <p className="mt-2 text-[14px] text-danger-ink">{errorText(revoke.error)}</p>}
    </Dialog>
  );
}
