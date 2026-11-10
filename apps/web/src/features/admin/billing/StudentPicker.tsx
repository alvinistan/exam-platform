import { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { Button, Skeleton, TextField } from '@/components/ui';
import { useUsers } from '@/features/admin/users/api';

export interface PickedStudent {
  id: string;
  name: string;
  email: string;
}

/** Search students by name or email and add them to a list; at most {@code max}. */
export function StudentPicker({
  value,
  onChange,
  error,
  max = 500,
}: {
  value: PickedStudent[];
  onChange: (students: PickedStudent[]) => void;
  error?: string | undefined;
  max?: number;
}) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const t = window.setTimeout(() => setQ(text.trim()), 300);
    return () => window.clearTimeout(t);
  }, [text]);
  const users = useUsers({ q, role: 'STUDENT', status: 'ACTIVE', page: 0 });
  const chosen = new Set(value.map((s) => s.id));
  const results = q ? (users.data?.items ?? []).filter((u) => !chosen.has(u.id)) : [];
  const add = (s: PickedStudent) => {
    if (value.length < max) onChange([...value, s]);
  };

  return (
    <div className="flex flex-col gap-2">
      <TextField
        label="Students"
        type="search"
        placeholder="Search by name or email"
        autoComplete="off"
        value={text}
        onChange={(e) => setText(e.target.value)}
        help={`${value.length} chosen${value.length >= max ? ` (the most at once is ${max})` : ''}.`}
        error={error}
      />
      {q && (
        <div className="max-h-48 overflow-y-auto rounded-md border border-border" aria-live="polite">
          {users.isPending ? (
            <Skeleton className="m-2 h-8" />
          ) : results.length === 0 ? (
            <p className="px-3 py-2 text-[14px] text-muted">No other students match “{q}”.</p>
          ) : (
            <ul>
              {results.map((u) => (
                <li
                  key={u.id}
                  className="flex items-center justify-between gap-3 border-b border-border px-3 py-1.5 last:border-b-0"
                >
                  <span className="min-w-0 text-[14.5px]">
                    <span className="font-semibold">{u.displayName}</span> <span className="break-all text-muted">{u.email}</span>
                  </span>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={value.length >= max}
                    onClick={() => add({ id: u.id, name: u.displayName, email: u.email })}
                    aria-label={`Add ${u.displayName}`}
                  >
                    Add
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" aria-label="Chosen students">
          {value.map((s) => (
            <li key={s.id} className="inline-flex items-center gap-1 rounded-full bg-surface-2 py-1 pr-1 pl-3 text-[13.5px]">
              {s.name}
              <button
                type="button"
                className="rounded-full p-0.5 text-muted hover:bg-border hover:text-ink"
                aria-label={`Remove ${s.name}`}
                onClick={() => onChange(value.filter((x) => x.id !== s.id))}
              >
                <X aria-hidden="true" className="size-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
