import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export type BadgeTone = 'neutral' | 'free' | 'premium' | 'warning' | 'danger' | 'primary';

const tones: Record<BadgeTone, string> = {
  neutral: 'bg-surface-2 text-ink-2',
  free: 'bg-success-soft text-success-ink',
  premium: 'bg-premium-soft text-premium-ink',
  warning: 'bg-warning-soft text-warning-ink',
  danger: 'bg-danger-soft text-danger-ink',
  primary: 'bg-primary-soft text-primary-ink',
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
}

/** Short status label. Always carries text, so meaning never relies on colour alone. */
export function Badge({ tone = 'neutral', className, ...rest }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-1 text-[13px] font-semibold',
        tones[tone],
        className,
      )}
      {...rest}
    />
  );
}
