import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Lock } from 'lucide-react';
import {
  Alert,
  Badge,
  Button,
  Card,
  CheckboxField,
  Dialog,
  EmptyState,
  ErrorState,
  SelectField,
  Skeleton,
  TextField,
} from '@/components/ui';
import { accessKeys, describeReason } from '@/features/access/api';
import { modeLabels, type Mode } from '@/features/exams/api';
import { useUserNames } from '@/features/admin/questions/api';
import { useUsers } from '@/features/admin/users/api';
import { ApiError } from '@/lib/api/problem';
import {
  accessAdminKeys,
  grantEntitlement,
  revokeEntitlement,
  savePolicy,
  useEntitlements,
  usePolicies,
  type Entitlement,
  type FeatureParams,
  type Policy,
  type PositionParams,
  type RuleCode,
  type WelcomeParams,
} from './api';

const MODES: Mode[] = ['PRACTICE', 'MOCK', 'USER_FRIENDLY'];
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

function useInvalidateAccess() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: accessAdminKeys.all });
    void queryClient.invalidateQueries({ queryKey: accessKeys.all });
  };
}

const errorText = (error: unknown) =>
  error instanceof ApiError
    ? error.code === 'CONFLICT'
      ? 'Someone else changed this rule. Reload the page and try again.'
      : (error.problem?.errors?.[0]?.message ?? error.message)
    : 'Please try again.';

/** Who gets what for free, which exams that makes free, and Premium access granted by hand (Phase 8). */
export function AccessPage() {
  const policies = usePolicies();
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight">Access</h1>
        <p className="text-muted">
          Decide which exams are free. Everything else needs Premium. Changes apply to the next session a student starts.
        </p>
      </div>
      {policies.isPending ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          <p role="status" className="sr-only">
            Loading access rules…
          </p>
          <Skeleton className="h-64 w-full" />
        </div>
      ) : policies.isError ? (
        <ErrorState title="We couldn’t load the access rules" onRetry={() => void policies.refetch()} />
      ) : (
        <>
          <RulesCard policies={policies.data.policies} />
          <FreeListCard exams={policies.data.exams} />
        </>
      )}
      <PremiumAccessCard />
    </div>
  );
}

function find<P>(policies: Policy[], code: RuleCode) {
  return policies.find((p) => p.ruleCode === code) as Policy<P> | undefined;
}

function RulesCard({ policies }: { policies: Policy[] }) {
  const position = find<PositionParams>(policies, 'FA1_CATALOGUE_POSITION');
  const features = find<FeatureParams>(policies, 'FA4_FREE_FEATURES');
  const welcome = find<WelcomeParams>(policies, 'FA2_WELCOME_ALLOWANCE');
  return (
    <Card className="flex flex-col gap-6 p-6">
      <h2 className="text-lg font-bold">Free access rules</h2>
      {position && <PositionRule key={`p${position.version}`} policy={position} />}
      {features && <FeatureRule key={`f${features.version}`} policy={features} />}
      {welcome && <WelcomeRule key={`w${welcome.version}`} policy={welcome} />}
    </Card>
  );
}

function RuleForm(props: {
  title: string;
  help: string;
  rule: RuleCode;
  version: number;
  value: () => { active: boolean; params: object } | string;
  dirty: boolean;
  children: ReactNode;
}) {
  const invalidate = useInvalidateAccess();
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: (v: { active: boolean; params: object }) => savePolicy(props.rule, props.version, v.active, v.params),
    onSuccess: invalidate,
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const value = props.value();
    if (typeof value === 'string') {
      setProblem(value);
      return;
    }
    setProblem(null);
    save.mutate(value);
  };
  const headingId = `rule-${props.rule}`;
  return (
    <form
      noValidate
      onSubmit={submit}
      aria-labelledby={headingId}
      className="flex flex-col gap-3 border-t border-border pt-5 first-of-type:border-t-0 first-of-type:pt-0"
    >
      <div>
        <h3 id={headingId} className="font-bold">
          {props.title}
        </h3>
        <p className="text-[14.5px] text-muted">{props.help}</p>
      </div>
      {props.children}
      {(problem ?? (save.isError ? errorText(save.error) : null)) && (
        <p className="text-[14px] text-danger-ink" role="alert">
          {problem ?? errorText(save.error)}
        </p>
      )}
      <div className="flex items-center gap-3">
        <Button type="submit" variant="secondary" size="sm" loading={save.isPending} disabled={!props.dirty}>
          Save
        </Button>
        {save.isSuccess && !props.dirty && <span className="text-[14px] text-success-ink">Saved.</span>}
      </div>
    </form>
  );
}

function PositionRule({ policy }: { policy: Policy<PositionParams> }) {
  const [active, setActive] = useState(policy.active);
  const [count, setCount] = useState(String(policy.params.count ?? 6));
  const [scope, setScope] = useState<PositionParams['scope']>(policy.params.scope ?? 'GLOBAL');
  const dirty = active !== policy.active || count !== String(policy.params.count) || scope !== policy.params.scope;
  return (
    <RuleForm
      title="Free exams by catalogue position"
      help="The first exams in the catalogue order are free. Set each exam’s position in its Details step (More options)."
      rule="FA1_CATALOGUE_POSITION"
      version={policy.version}
      dirty={dirty}
      value={() =>
        /^\d{1,4}$/.test(count) && Number(count) <= 1000
          ? { active, params: { count: Number(count), scope } }
          : 'Enter how many exams are free: a whole number from 0 to 1000.'
      }
    >
      <CheckboxField label="Make the first exams free" checked={active} onChange={(e) => setActive(e.target.checked)} />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="How many"
          inputMode="numeric"
          value={count}
          onChange={(e) => setCount(e.target.value)}
          disabled={!active}
        />
        <SelectField
          label="Counted"
          value={scope}
          onChange={(e) => setScope(e.target.value as PositionParams['scope'])}
          disabled={!active}
        >
          <option value="GLOBAL">Across the whole catalogue</option>
          <option value="CATEGORY">In each category</option>
        </SelectField>
      </div>
    </RuleForm>
  );
}

function FeatureRule({ policy }: { policy: Policy<FeatureParams> }) {
  const [modes, setModes] = useState<Mode[]>(policy.active ? policy.params.modes : MODES);
  const original = policy.active ? policy.params.modes : MODES;
  const dirty = MODES.some((m) => modes.includes(m) !== original.includes(m));
  return (
    <RuleForm
      title="Modes on free exams"
      help="Which ways of studying a free exam offers without Premium."
      rule="FA4_FREE_FEATURES"
      version={policy.version}
      dirty={dirty}
      value={() => (modes.length ? { active: true, params: { modes } } : 'Choose at least one mode.')}
    >
      <fieldset className="flex flex-wrap gap-x-6 gap-y-2">
        <legend className="sr-only">Free modes</legend>
        {MODES.map((m) => (
          <CheckboxField
            key={m}
            label={modeLabels[m]}
            checked={modes.includes(m)}
            onChange={(e) => setModes(e.target.checked ? [...modes, m] : modes.filter((x) => x !== m))}
          />
        ))}
      </fieldset>
    </RuleForm>
  );
}

function WelcomeRule({ policy }: { policy: Policy<WelcomeParams> }) {
  const [active, setActive] = useState(policy.active);
  const [attempts, setAttempts] = useState(String(policy.params.attempts ?? 2));
  const dirty = active !== policy.active || attempts !== String(policy.params.attempts);
  return (
    <RuleForm
      title="Welcome attempts"
      help="Lets each new student try a few sessions on premium exams, using trial questions. A session counts when it starts."
      rule="FA2_WELCOME_ALLOWANCE"
      version={policy.version}
      dirty={dirty}
      value={() =>
        /^\d{1,2}$/.test(attempts) && Number(attempts) >= 1 && Number(attempts) <= 50
          ? { active, params: { attempts: Number(attempts) } }
          : 'Enter a number of attempts from 1 to 50.'
      }
    >
      <CheckboxField label="Give new students welcome attempts" checked={active} onChange={(e) => setActive(e.target.checked)} />
      <div className="max-w-xs">
        <TextField
          label="Attempts per student"
          inputMode="numeric"
          value={attempts}
          onChange={(e) => setAttempts(e.target.value)}
          disabled={!active}
        />
      </div>
    </RuleForm>
  );
}

function FreeListCard({
  exams,
}: {
  exams: { examId: string; title: string; displayOrder: number | null; access: 'FREE' | 'PREMIUM'; reason: string }[];
}) {
  return (
    <Card className="flex flex-col gap-4 p-6">
      <div>
        <h2 className="text-lg font-bold">Published exams</h2>
        <p className="text-[14.5px] text-muted">In catalogue order, with what the rules above make of each one.</p>
      </div>
      {exams.length === 0 ? (
        <p className="text-muted">No published exams yet. They appear here a few seconds after you publish them.</p>
      ) : (
        <div className="overflow-x-auto rounded-md border border-border">
          <table className="w-full min-w-[520px] border-collapse text-[14.5px]">
            <caption className="sr-only">Published exams and their access</caption>
            <thead>
              <tr className="border-b border-border bg-[#fafbfc] text-left text-[12.5px] font-bold tracking-wider text-muted uppercase">
                <th scope="col" className="px-4 py-3">
                  Position
                </th>
                <th scope="col" className="px-4 py-3">
                  Exam
                </th>
                <th scope="col" className="px-4 py-3">
                  Access
                </th>
                <th scope="col" className="px-4 py-3">
                  Why
                </th>
              </tr>
            </thead>
            <tbody>
              {exams.map((e) => (
                <tr key={e.examId} className="border-b border-border last:border-b-0">
                  <td className="px-4 py-3 tabular-nums">{e.displayOrder ?? '—'}</td>
                  <td className="px-4 py-3">
                    <Link to={`/admin/exams/${e.examId}/details`} className="font-semibold">
                      {e.title || 'Untitled exam'}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    {e.access === 'FREE' ? (
                      <Badge tone="free">Free</Badge>
                    ) : (
                      <Badge tone="premium">
                        <Lock aria-hidden="true" className="size-3.5" /> Premium
                      </Badge>
                    )}
                  </td>
                  <td className="px-4 py-3 text-ink-2">{describeReason(e.reason)}</td>
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
