import { forwardRef, useId, type SelectHTMLAttributes } from 'react';
import { ChevronDown } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface SelectFieldProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: string;
  /** Visually hide the label (it stays available to screen readers). */
  hideLabel?: boolean;
}

/** Native select (best keyboard and screen-reader support) styled to match the design system. */
export const SelectField = forwardRef<HTMLSelectElement, SelectFieldProps>(function SelectField(
  { label, hideLabel = false, className, children, ...rest },
  ref,
) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className={cn('text-sm font-semibold text-ink', hideLabel && 'sr-only')}>
        {label}
      </label>
      <div className="relative">
        <select
          ref={ref}
          id={id}
          className={cn(
            'min-h-11 w-full appearance-none rounded-md border border-border-strong bg-surface py-2.5 pr-10 pl-3 text-[15px] text-ink',
            'focus:border-primary focus:shadow-[0_0_0_3px_var(--color-primary-soft)] focus:outline-none',
            className,
          )}
          {...rest}
        >
          {children}
        </select>
        <ChevronDown aria-hidden="true" className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted" />
      </div>
    </div>
  );
});
