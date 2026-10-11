import { cn } from '@/lib/cn';

/** Loading placeholder. Decorative; pair it with a visually hidden "Loading…" status for screen readers. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        'animate-shimmer rounded-sm bg-[linear-gradient(90deg,#eef0f4_0%,#f6f7f9_50%,#eef0f4_100%)] bg-[length:200%_100%]',
        className,
      )}
    />
  );
}
