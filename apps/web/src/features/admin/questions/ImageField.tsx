import { useId, useRef, useState } from 'react';
import { ImagePlus, Trash2 } from 'lucide-react';
import { Button, TextField } from '@/components/ui';
import { ApiError } from '@/lib/api/problem';
import { uploadImage } from './api';

export interface ImageValue {
  key: string;
  url: string | null;
  alt: string;
}

interface ImageFieldProps {
  label: string;
  value: ImageValue | null;
  onChange: (value: ImageValue | null) => void;
  altError?: string | undefined;
}

/**
 * Optional image for a question or an option (FR-QB-02): PNG, JPEG or WebP up to 2 MB, with alt text for
 * screen-reader users. The server checks and re-encodes every upload.
 */
export function ImageField({ label, value, onChange, altError }: ImageFieldProps) {
  const input = useRef<HTMLInputElement>(null);
  const inputId = useId();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pick = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    if (file.size > 2 * 1024 * 1024) {
      setError('Images must be at most 2 MB.');
      return;
    }
    setBusy(true);
    try {
      const stored = await uploadImage(file);
      onChange({ key: stored.key, url: stored.url, alt: value?.alt ?? '' });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'The image couldn’t be uploaded.');
    } finally {
      setBusy(false);
      if (input.current) input.current.value = '';
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <input
        ref={input}
        id={inputId}
        type="file"
        accept="image/png,image/jpeg,image/webp"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(e) => void pick(e.target.files?.[0])}
      />
      {value ? (
        <div className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-start">
          {value.url ? (
            <img src={value.url} alt="" className="h-20 w-28 flex-none rounded-sm border border-border object-contain" />
          ) : (
            <span className="flex h-20 w-28 flex-none items-center justify-center rounded-sm bg-surface-2 text-[12px] text-muted">
              Saved image
            </span>
          )}
          <div className="flex min-w-0 flex-1 flex-col gap-2">
            <TextField
              label={`${label}: description for screen readers`}
              value={value.alt}
              maxLength={250}
              error={altError}
              onChange={(e) => onChange({ ...value, alt: e.target.value })}
            />
            <div className="flex gap-2">
              <Button size="sm" variant="secondary" loading={busy} onClick={() => input.current?.click()}>
                Replace image
              </Button>
              <Button size="sm" variant="ghost" onClick={() => onChange(null)} aria-label={`Remove ${label.toLowerCase()}`}>
                <Trash2 aria-hidden="true" className="size-4" /> Remove
              </Button>
            </div>
          </div>
        </div>
      ) : (
        <div>
          <Button size="sm" variant="ghost" loading={busy} onClick={() => input.current?.click()} aria-label={`Add ${label.toLowerCase()}`}>
            <ImagePlus aria-hidden="true" className="size-4" /> Add image
          </Button>
        </div>
      )}
      {error && (
        <p role="alert" className="text-[13px] text-danger-ink">
          {error}
        </p>
      )}
    </div>
  );
}
