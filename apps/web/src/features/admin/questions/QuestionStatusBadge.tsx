import { Badge } from '@/components/ui';
import { statusLabels, type QuestionStatus } from './api';

export function QuestionStatusBadge({ status }: { status: QuestionStatus }) {
  const tone = status === 'PUBLISHED' ? 'free' : status === 'REVIEWED' ? 'primary' : status === 'DRAFT' ? 'warning' : 'neutral';
  return <Badge tone={tone}>{statusLabels[status]}</Badge>;
}
