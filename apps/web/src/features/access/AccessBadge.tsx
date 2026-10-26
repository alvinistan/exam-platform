import { Lock, LockOpen } from 'lucide-react';
import { Badge } from '@/components/ui';
import type { AccessStatus } from './api';

/** Free, Premium (locked) or Premium (unlocked). Always text plus icon, never colour alone. */
export function AccessBadge({ status }: { status: AccessStatus | undefined }) {
  if (!status || status.reason === 'EXAM_UNAVAILABLE') return null;
  if (status.access === 'FREE') return <Badge tone="free">Free</Badge>;
  if (status.access === 'PREMIUM_UNLOCKED') {
    return (
      <Badge tone="premium">
        <LockOpen aria-hidden="true" className="size-3.5" /> Premium · unlocked
      </Badge>
    );
  }
  return (
    <Badge tone="premium">
      <Lock aria-hidden="true" className="size-3.5" /> Premium
    </Badge>
  );
}
