import { useState } from 'react';
import { cn } from '@/lib/cn';

/** Profile photo, or the person's initials when there is none or it fails to load. */
export function Avatar({ name, photoUrl, className }: { name: string; photoUrl?: string | null | undefined; className?: string }) {
  const [failed, setFailed] = useState(false);
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');
  if (photoUrl && !failed) {
    return (
      <img
        src={photoUrl}
        alt=""
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
        className={cn('size-8 flex-none rounded-full object-cover', className)}
      />
    );
  }
  return (
    <span
      aria-hidden="true"
      className={cn(
        'flex size-8 flex-none items-center justify-center rounded-full bg-primary-soft text-[13px] font-bold text-primary-ink',
        className,
      )}
    >
      {initials || '?'}
    </span>
  );
}
