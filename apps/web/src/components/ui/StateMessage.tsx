import type { ReactNode } from 'react';
import { CircleAlert, Inbox, type LucideIcon } from 'lucide-react';
import { Button } from './Button';

interface StateMessageProps {
  title: string;
  children?: ReactNode;
  icon?: LucideIcon;
  action?: ReactNode;
  headingLevel?: 'h1' | 'h2';
}

function StateMessage({ title, children, icon: Icon = Inbox, action, headingLevel = 'h2' }: StateMessageProps) {
  const Heading = headingLevel;
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-3 px-4 py-14 text-center">
      <span className="flex size-12 items-center justify-center rounded-full bg-surface-2 text-muted">
        <Icon aria-hidden="true" className="size-6" />
      </span>
      <Heading className="text-xl font-bold text-ink">{title}</Heading>
      {children && <div className="text-muted">{children}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

/** Nothing to show yet: explain why and offer the next step. */
export function EmptyState(props: StateMessageProps) {
  return <StateMessage {...props} />;
}

interface ErrorStateProps {
  title?: string;
  children?: ReactNode;
  onRetry?: () => void;
  correlationId?: string | undefined;
}

/** Something failed: say so plainly, offer a retry, and show the reference support will ask for. */
export function ErrorState({ title = 'Something went wrong', children, onRetry, correlationId }: ErrorStateProps) {
  return (
    <div role="alert">
      <StateMessage
        title={title}
        icon={CircleAlert}
        action={
          onRetry && (
            <Button variant="secondary" onClick={onRetry}>
              Try again
            </Button>
          )
        }
      >
        {children ?? <p>Please try again in a moment.</p>}
        {correlationId && <p className="mt-2 text-[13px] text-muted">Reference: {correlationId}</p>}
      </StateMessage>
    </div>
  );
}
