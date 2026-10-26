import type { ReactNode } from 'react';
import { CircleAlert, CircleCheck, Info, Lock, TriangleAlert } from 'lucide-react';
import { cn } from '@/lib/cn';

export type AlertTone = 'info' | 'warning' | 'success' | 'error' | 'premium';

const styles: Record<AlertTone, { box: string; Icon: typeof Info }> = {
  info: { box: 'bg-primary-soft border-[#c9d4f7] text-primary-ink', Icon: Info },
  warning: { box: 'bg-warning-soft border-[#f2d8a6] text-warning-ink', Icon: TriangleAlert },
  success: { box: 'bg-success-soft border-[#bfe3cb] text-success-ink', Icon: CircleCheck },
  error: { box: 'bg-danger-soft border-[#f1c2c0] text-danger-ink', Icon: CircleAlert },
  premium: { box: 'bg-premium-soft border-[#d9ccfb] text-premium-ink', Icon: Lock },
};

export interface AlertProps {
  tone?: AlertTone;
  title?: string;
  children?: ReactNode;
  className?: string;
}

/**
 * Inline message. Errors and warnings use role="alert" so screen readers announce them immediately;
 * info and success use role="status".
 */
export function Alert({ tone = 'info', title, children, className }: AlertProps) {
  const { box, Icon } = styles[tone];
  const urgent = tone === 'error' || tone === 'warning';
  return (
    <div
      role={urgent ? 'alert' : 'status'}
      className={cn('flex items-start gap-3 rounded-md border px-4 py-3.5 text-[14.5px]', box, className)}
    >
      <Icon aria-hidden="true" className="mt-0.5 size-5 flex-none" />
      <div className="flex flex-col gap-1">
        {title && <p className="font-semibold">{title}</p>}
        {children}
      </div>
    </div>
  );
}
