import { Lock, LockOpen } from 'lucide-react';
import { Badge } from '@/components/ui';
import { examAccessSummary, type AccessStatus } from './api';

/** Free to try, Premium (locked) or Premium (unlocked). Always text plus icon, never colour alone. */
export function AccessBadge({ status }: { status: AccessStatus | undefined }) {
  const summary = examAccessSummary(status);
  if (!summary) return null;
  if (summary === 'FREE') return <Badge tone="free">Free to try</Badge>;
  if (summary === 'PREMIUM_OPEN') {
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
