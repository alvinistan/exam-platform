import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/cn';

interface DialogProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: ReactNode;
  children?: ReactNode;
  /** Buttons, right-aligned at the bottom. */
  footer?: ReactNode;
  className?: string;
}

/**
 * Modal dialog built on the native <dialog> element: the browser traps focus, makes the page behind inert,
 * closes on Escape and returns focus to the opener.
 */
export function Dialog({ open, onClose, title, description, children, footer, className }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
    } else if (!open && dialog.open) {
      if (typeof dialog.close === 'function') dialog.close();
      else dialog.removeAttribute('open');
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === ref.current) onClose(); // click on the backdrop
      }}
      className={cn(
        'm-auto max-h-[calc(100dvh-32px)] w-[min(520px,calc(100vw-32px))] overflow-y-auto rounded-lg border border-border bg-surface p-0 text-ink shadow-raised',
        'backdrop:bg-ink/40',
        className,
      )}
    >
      {open && (
        <div className="flex flex-col gap-4 p-6">
          <div className="flex items-start justify-between gap-4">
            <h2 id={titleId} className="text-lg font-bold">
              {title}
            </h2>
            <button
              type="button"
              onClick={onClose}
              className="-m-2 rounded-sm p-2 text-muted hover:bg-surface-2 hover:text-ink"
              aria-label="Close"
            >
              <X aria-hidden="true" className="size-5" />
            </button>
          </div>
          {description && (
            <div id={descriptionId} className="text-muted">
              {description}
            </div>
          )}
          {children}
          {footer && <div className="flex flex-wrap justify-end gap-3 pt-2">{footer}</div>}
        </div>
      )}
    </dialog>
  );
}
