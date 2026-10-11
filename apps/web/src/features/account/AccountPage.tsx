import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { z } from 'zod';
import { Alert, Avatar, Badge, Button, Card, Dialog, TextField } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthContext';
import type { Me } from '@/lib/auth/types';
import { deleteAccount, updateProfile } from './api';

const profileSchema = z.object({
  displayName: z
    .string()
    .trim()
    .min(1, 'Enter the name to show on your results.')
    .max(120, 'Use 120 characters or fewer.'),
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
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit((values) => save.mutate(values))}
        >
          <h2 className="text-lg font-bold">Profile</h2>
          <TextField
            label="Display name"
            help="Shown on your dashboard and results."
            autoComplete="name"
            error={form.formState.errors.displayName?.message}
            {...form.register('displayName')}
          />
          {save.isSuccess && !form.formState.isDirty && (
            <Alert tone="success" title="Profile saved" />
          )}
          {save.isError && <Alert tone="error" title="Couldn’t save your profile">{errorMessage(save.error)}</Alert>}
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

      <Card className="flex flex-col gap-3 border-danger-soft p-6">
        <h2 className="text-lg font-bold">Delete account</h2>
        <p className="text-muted">
          Removes your name, email and photo and signs you out everywhere. Anonymous statistics are kept. This can’t be
          undone.
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
        {remove.isError && <Alert tone="error" title="Couldn’t delete your account">{errorMessage(remove.error)}</Alert>}
      </Dialog>
    </div>
  );
}
