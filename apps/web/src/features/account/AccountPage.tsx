import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { Alert, Avatar, Badge, Button, Card, Dialog, Skeleton, TextField } from '@/components/ui';
import { Link } from 'react-router';
import { describeLimits, formatPrice, useMySubscription, type ActivePlan } from '@/features/billing/api';
import { ApiError } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Me } from '@/lib/auth/types';
import { deleteAccount, updateProfile } from './api';

const profileSchema = z.object({
  displayName: z.string().trim().min(1, 'Enter the name to show on your results.').max(120, 'Use 120 characters or fewer.'),
});
type ProfileForm = z.infer<typeof profileSchema>;

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === 'CONFLICT') {
    return 'Your profile was changed in another window. Reload the page and try again.';
  }
  return error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
}

export function AccountPage() {
  const { state } = useAuth();
  if (state.status !== 'signedIn') return null; // RequireAuth wraps this page
  return <Account user={state.user} />;
}

function Account({ user }: { user: Me }) {
  const { setUser, signOut } = useAuth();
  const [confirmDelete, setConfirmDelete] = useState(false);

  const form = useForm<ProfileForm>({
    resolver: zodResolver(profileSchema),
    defaultValues: { displayName: user.displayName },
  });

  const save = useMutation({
    mutationFn: (values: ProfileForm) => updateProfile(user.version, values),
    onSuccess: (updated) => {
      setUser(updated);
      form.reset({ displayName: updated.displayName });
    },
  });

  const remove = useMutation({
    mutationFn: deleteAccount,
    // Signing out sends the browser home (see RequireAuth).
    onSuccess: () => signOut(),
  });

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6 px-4 py-10 sm:px-8">
      <div className="flex items-center gap-4">
        <Avatar name={user.displayName} photoUrl={user.photoUrl} className="size-14 text-lg" />
        <div className="min-w-0">
          <h1 className="truncate text-2xl font-extrabold tracking-tight">Your account</h1>
          <p className="truncate text-muted">{user.email}</p>
        </div>
      </div>

      <Card className="p-6">
        <form noValidate className="flex flex-col gap-4" onSubmit={form.handleSubmit((values) => save.mutate(values))}>
          <h2 className="text-lg font-bold">Profile</h2>
          <TextField
            label="Display name"
            help="Shown on your dashboard and results."
            autoComplete="name"
            error={form.formState.errors.displayName?.message}
            {...form.register('displayName')}
          />
          {save.isSuccess && !form.formState.isDirty && <Alert tone="success" title="Profile saved" />}
          {save.isError && (
            <Alert tone="error" title="Couldn’t save your profile">
              {errorMessage(save.error)}
            </Alert>
          )}
          <div>
            <Button type="submit" loading={save.isPending} disabled={!form.formState.isDirty}>
              Save changes
            </Button>
          </div>
        </form>
      </Card>

      <Card className="flex flex-col gap-3 p-6">
        <h2 className="text-lg font-bold">Sign-in and access</h2>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-[15px]">
          <dt className="text-muted">Signed in with</dt>
          <dd>Google ({user.email})</dd>
          <dt className="text-muted">Access</dt>
          <dd className="flex flex-wrap gap-2">
            {user.roles.map((role) => (
              <Badge key={role} tone={role === 'ADMIN' ? 'primary' : 'neutral'}>
                {role === 'ADMIN' ? 'Administrator' : 'Student'}
              </Badge>
            ))}
          </dd>
        </dl>
      </Card>

      <PlanCard />

      <Card className="flex flex-col gap-3 border-danger-soft p-6">
        <h2 className="text-lg font-bold">Delete account</h2>
        <p className="text-muted">
          Removes your name, email and photo and signs you out everywhere. Anonymous statistics are kept. This can’t be undone.
        </p>
        <div>
          <Button variant="danger" onClick={() => setConfirmDelete(true)}>
            Delete my account
          </Button>
        </div>
      </Card>

      <Dialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        title="Delete your account?"
        description="Your personal details are removed and you are signed out on every device. This can’t be undone."
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmDelete(false)}>
              Keep my account
            </Button>
            <Button variant="danger" loading={remove.isPending} onClick={() => remove.mutate()}>
              Delete account
            </Button>
          </>
        }
      >
        {remove.isError && (
          <Alert tone="error" title="Couldn’t delete your account">
            {errorMessage(remove.error)}
          </Alert>
        )}
      </Dialog>
    </div>
  );
}

const longDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'long' });

const paymentLabel = {
  SUCCEEDED: 'Paid',
  PENDING: 'Waiting for confirmation',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
} as const;
const paymentTone = { SUCCEEDED: 'free', PENDING: 'warning', FAILED: 'danger', CANCELLED: 'neutral' } as const;

const modeWords = { PRACTICE: 'Practice', MOCK: 'Mock', USER_FRIENDLY: 'Adaptive' } as const;

/** One active plan: what it covers, until when, and attempts left per exam type on each exam. */
function ActivePlanRow({ plan }: { plan: ActivePlan }) {
  return (
    <li className="flex flex-col gap-2 rounded-md border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-2">
          <Badge tone={plan.source === 'CAMPAIGN' ? 'free' : 'premium'}>
            {plan.source === 'MANUAL' ? 'Given' : plan.source === 'CAMPAIGN' ? 'Free offer' : 'Plan'}
          </Badge>
          <span className="font-semibold">{plan.name}</span>
        </span>
        <span className="text-[13.5px] text-muted">Until {longDate.format(new Date(plan.endsAt))}</span>
      </div>
      {plan.scopeType === 'ALL' && (
        <p className="text-[14px] text-ink-2">
          Every exam · {describeLimits(plan.limits).join(' · ') || 'No exam types included'}
        </p>
      )}
      {plan.exams.length > 0 && (
        <ul className="flex flex-col divide-y divide-border text-[14px]">
          {plan.exams.map((exam) => (
            <li key={exam.examId} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2">
              <Link to={`/exams/${exam.slug}`} className="font-semibold">
                {exam.title || 'Exam'}
              </Link>
              <span className="text-ink-2">
                {exam.modes
                  .map((m) =>
                    m.limit === null ? `${modeWords[m.mode]}: unlimited` : `${modeWords[m.mode]}: ${m.left} of ${m.limit} left`,
                  )
                  .join(' · ')}
              </span>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

/** The student's Premium status and payments, decided by subscription-service (FR-SUB-06, FR-SUB-08). */
function PlanCard() {
  const me = useMySubscription();
  return (
    <Card className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-bold">Your plan</h2>
        <Link to="/plans" className="font-semibold">
          {me.data?.premium ? 'Get another plan' : 'See plans'}
        </Link>
      </div>
      {me.isPending ? (
        <Skeleton className="h-6 w-1/2" />
      ) : me.isError ? (
        <p className="text-muted">We couldn’t load your plan just now.</p>
      ) : (
        <>
          {me.data.plans.length > 0 ? (
            <ul className="flex flex-col gap-3">
              {me.data.plans.map((plan) => (
                <ActivePlanRow key={plan.id} plan={plan} />
              ))}
            </ul>
          ) : (
            <p className="flex flex-wrap items-center gap-2">
              <Badge tone="free">Free</Badge>
              <span>
                Each exam gives you a few free attempts of every exam type. A plan gives you more, for one subject, a group of
                subjects or everything.
              </span>
            </p>
          )}
          {me.data.payments.length > 0 && (
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-[13px] font-bold tracking-wider text-muted uppercase">Payments</h3>
                <Link to="/account/payments" className="text-[14px] font-semibold">
                  All payments and receipts
                </Link>
              </div>
              <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
                {me.data.payments.slice(0, 5).map((p) => (
                  <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-[14.5px]">
                    <span>
                      <span className="font-semibold">{p.planName}</span>
                      <span className="block text-[13px] text-muted">
                        {longDate.format(new Date(p.createdAt))}
                        {p.provider === 'TEST' && ' · test payment'}
                        {p.status === 'SUCCEEDED' &&
                          p.premiumEndsAt &&
                          ` · Premium until ${longDate.format(new Date(p.premiumEndsAt))}`}
                        {p.failureReason === 'CHARGEDBACK' && ' · reversed'}
                        {p.refundedMinor > 0 && ` · ${formatPrice(p.refundedMinor, p.currency)} refunded`}
                      </span>
                    </span>
                    <span className="flex items-center gap-3">
                      <span className="tabular-nums">{formatPrice(p.amountMinor, p.currency)}</span>
                      <Badge tone={paymentTone[p.status]}>{paymentLabel[p.status]}</Badge>
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </Card>
  );
}
