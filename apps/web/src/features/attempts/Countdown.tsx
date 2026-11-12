import { useEffect, useRef, useState } from 'react';
import { Timer } from 'lucide-react';
import { cn } from '@/lib/cn';
import { clock } from './modes';

/** The countdown: amber under five minutes, red under one. Announced to screen readers only at those moments. */
export function Countdown({ seconds }: { seconds: number }) {
  const low = seconds < 300;
  const critical = seconds < 60;
  const [announce, setAnnounce] = useState('');
  const said = useRef<Set<string>>(new Set());
  useEffect(() => {
    const say = (key: string, text: string) => {
      if (!said.current.has(key)) {
        said.current.add(key);
        setAnnounce(text);
      }
    };
    if (seconds <= 60) say('1', 'One minute left.');
    else if (seconds <= 300) say('5', 'Five minutes left.');
  }, [seconds]);
  return (
    <div
      className={cn(
        'inline-flex min-h-11 items-center gap-2 rounded-md border px-3 font-bold tabular-nums',
        critical
          ? 'border-danger bg-danger-soft text-danger-ink'
          : low
            ? 'border-[#f2d8a6] bg-warning-soft text-warning-ink'
            : 'border-border-strong bg-surface',
      )}
    >
      <Timer aria-hidden="true" className="size-4" />
      <span>
        <span className="sr-only">Time left: </span>
        {clock(seconds)}
      </span>
      <span role="status" className="sr-only">
        {announce}
      </span>
    </div>
  );
}
