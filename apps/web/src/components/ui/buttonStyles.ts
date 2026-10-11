import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'premium';
export type ButtonSize = 'sm' | 'md' | 'lg';

const base =
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md border border-transparent font-semibold no-underline transition-colors cursor-pointer disabled:cursor-not-allowed disabled:border-border disabled:bg-surface-2 disabled:text-subtle';

const variants: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-white hover:bg-primary-hover hover:text-white',
  secondary: 'border-border-strong bg-surface text-ink hover:bg-surface-2 hover:text-ink',
  ghost: 'bg-transparent text-ink hover:bg-surface-2 hover:text-ink',
  danger: 'bg-danger text-white hover:bg-danger-ink hover:text-white',
  premium: 'bg-premium text-white hover:bg-premium-hover hover:text-white',
};

const sizes: Record<ButtonSize, string> = {
  sm: 'min-h-9 px-3 py-1.5 text-sm',
  md: 'min-h-11 px-[18px] py-2.5 text-[15px]',
  lg: 'min-h-[50px] px-[22px] py-3 text-base',
};

/** Class names for a button-styled element; use on links that should look like buttons. */
export function buttonClasses(variant: ButtonVariant = 'primary', size: ButtonSize = 'md', className?: string) {
  return cn(base, variants[variant], sizes[size], className);
}
