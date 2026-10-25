import { Badge } from '@/components/ui';
import { statusLabels, type ExamStatus } from './api';

export function ExamStatusBadge({ status }: { status: ExamStatus }) {
  const tone = status === 'PUBLISHED' ? 'free' : status === 'DRAFT' ? 'warning' : 'neutral';
  return <Badge tone={tone}>{statusLabels[status]}</Badge>;
}
