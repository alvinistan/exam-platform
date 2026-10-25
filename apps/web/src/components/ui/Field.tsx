import { forwardRef, useId, type InputHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string;
  help?: string;
  error?: string | undefined;
}

/** Labelled text input with help text and an error message wired up for assistive technology. */
export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { label, help, error, className, ...rest },
  ref,
) {
  const id = useId();
  const helpId = help ? `${id}-help` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-semibold text-ink">
        {label}
      </label>
      <input
        ref={ref}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'min-h-11 w-full rounded-md border border-border-strong bg-surface px-3 py-2.5 text-[15px] text-ink',
          'focus:border-primary focus:shadow-[0_0_0_3px_var(--color-primary-soft)] focus:outline-none',
          error && 'border-danger',
          className,
        )}
        {...rest}
      />
      {help && (
        <p id={helpId} className="text-[13px] text-muted">
          {help}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-[13px] text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
});

export interface TextAreaFieldProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'id'> {
  label: string;
  help?: string;
  error?: string | undefined;
}

/** Multi-line version of {@link TextField}. */
export const TextAreaField = forwardRef<HTMLTextAreaElement, TextAreaFieldProps>(function TextAreaField(
  { label, help, error, className, rows = 4, ...rest },
  ref,
) {
  const id = useId();
  const helpId = help ? `${id}-help` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-semibold text-ink">
        {label}
      </label>
      <textarea
        ref={ref}
        id={id}
        rows={rows}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'w-full rounded-md border border-border-strong bg-surface px-3 py-2.5 text-[15px] text-ink',
          'focus:border-primary focus:shadow-[0_0_0_3px_var(--color-primary-soft)] focus:outline-none',
          error && 'border-danger',
          className,
        )}
        {...rest}
      />
      {help && (
        <p id={helpId} className="text-[13px] text-muted">
          {help}
        </p>
      )}
      {error && (
        <p id={errorId} className="text-[13px] text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
});

export interface CheckboxFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'type'> {
  label: string;
  help?: string;
}

/** Checkbox with its label to the right and optional help text underneath. */
export const CheckboxField = forwardRef<HTMLInputElement, CheckboxFieldProps>(function CheckboxField(
  { label, help, className, ...rest },
  ref,
) {
  const id = useId();
  const helpId = help ? `${id}-help` : undefined;
  return (
    <div className={cn('flex items-start gap-2.5', className)}>
      <input
        ref={ref}
        id={id}
        type="checkbox"
        aria-describedby={helpId}
        className="mt-0.5 size-[18px] flex-none accent-primary"
        {...rest}
      />
      <div className="flex flex-col gap-0.5">
        <label htmlFor={id} className="text-[15px] font-medium text-ink">
          {label}
        </label>
        {help && (
          <p id={helpId} className="text-[13px] text-muted">
            {help}
          </p>
        )}
      </div>
    </div>
  );
});
