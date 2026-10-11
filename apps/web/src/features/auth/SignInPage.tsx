import { Navigate, useSearchParams } from 'react-router';
import { Alert, Button, Card } from '@/components/ui';
import { useAuth } from '@/lib/auth/AuthContext';
import { safeReturnTo } from '@/lib/auth/returnTo';

/** Messages for the error codes identity-service sends back after a failed sign-in. */
const errors: Record<string, { title: string; text: string }> = {
  cancelled: { title: 'Sign-in cancelled', text: 'You closed the Google window before finishing. Try again whenever you are ready.' },
  suspended: {
    title: 'Your account is suspended',
    text: 'You can’t sign in at the moment. Please contact support if you think this is a mistake.',
  },
  email_unverified: {
    title: 'Email not verified',
    text: 'Your Google account’s email address isn’t verified. Verify it with Google, then sign in again.',
  },
  account_conflict: {
    title: 'Email already in use',
    text: 'Another account here already uses this email address. Please contact support.',
  },
  expired: { title: 'That sign-in link expired', text: 'Sign-in links work once and only for 10 minutes. Please try again.' },
  invalid_state: {
    title: 'Sign-in couldn’t be completed',
    text: 'Please start again from this page, in the same browser.',
  },
  not_configured: {
    title: 'Sign-in isn’t set up yet',
    text: 'Google sign-in hasn’t been configured on this server.',
  },
};
const fallbackError = { title: 'Sign-in failed', text: 'Something went wrong while talking to Google. Please try again.' };

export function SignInPage() {
  const { state, signIn } = useAuth();
  const [params] = useSearchParams();
  const returnTo = safeReturnTo(params.get('returnTo'));
  const errorCode = params.get('error');
  const error = errorCode ? (errors[errorCode] ?? fallbackError) : null;

  if (state.status === 'signedIn') return <Navigate to={returnTo} replace />;

  return (
    <div className="mx-auto flex max-w-md flex-col gap-6 px-4 py-16">
      <Card className="flex flex-col gap-5 p-8">
        <div className="flex flex-col gap-2">
          <h1 className="text-2xl font-extrabold tracking-tight">Sign in to Leenex Labs</h1>
          <p className="text-muted">Use your Google account. We only receive your name, email address and photo.</p>
        </div>
        {error && (
          <Alert tone={errorCode === 'cancelled' ? 'info' : 'error'} title={error.title}>
            <p>{error.text}</p>
          </Alert>
        )}
        <Button size="lg" onClick={() => signIn(returnTo)} disabled={state.status === 'loading'}>
          Continue with Google
        </Button>
        <p className="text-[13px] text-muted">
          New here? Signing in creates your free account. You can delete it at any time from your account page.
        </p>
      </Card>
    </div>
  );
}
