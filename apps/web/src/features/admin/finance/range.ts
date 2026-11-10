/** Date ranges for finance reports, as calendar days ("2026-11-10") in the administrator's own time zone. */

export type Preset = '7D' | '30D' | 'THIS_MONTH' | 'LAST_MONTH' | 'THIS_YEAR';

export const PRESETS: { value: Preset; label: string }[] = [
  { value: '7D', label: 'Last 7 days' },
  { value: '30D', label: 'Last 30 days' },
  { value: 'THIS_MONTH', label: 'This month' },
  { value: 'LAST_MONTH', label: 'Last month' },
  { value: 'THIS_YEAR', label: 'This year' },
];

export const isoDay = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** A day string as a local date at noon (safe from daylight-saving edges). */
export const dayDate = (day: string) => new Date(`${day}T12:00:00`);

export function presetRange(p: Preset, today = new Date()): { from: string; to: string } {
  const y = today.getFullYear();
  const m = today.getMonth();
  const back = (days: number) => new Date(y, m, today.getDate() - days);
  switch (p) {
    case '7D':
      return { from: isoDay(back(6)), to: isoDay(today) };
    case '30D':
      return { from: isoDay(back(29)), to: isoDay(today) };
    case 'THIS_MONTH':
      return { from: isoDay(new Date(y, m, 1)), to: isoDay(today) };
    case 'LAST_MONTH':
      return { from: isoDay(new Date(y, m - 1, 1)), to: isoDay(new Date(y, m, 0)) };
    case 'THIS_YEAR':
      return { from: isoDay(new Date(y, 0, 1)), to: isoDay(today) };
  }
}

/** Every day from `from` to `to` inclusive. */
export function daysBetween(from: string, to: string): string[] {
  const out: string[] = [];
  for (let d = dayDate(from); isoDay(d) <= to && out.length < 400; d.setDate(d.getDate() + 1)) out.push(isoDay(d));
  return out;
}
