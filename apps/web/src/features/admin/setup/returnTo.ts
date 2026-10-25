/**
 * Pages reached from the exam setup (question editor, import) carry ?returnTo=/admin/exams/… so they can lead back.
 * Only paths inside the administration area are accepted, never another site.
 */
export function safeReturnTo(value: string | null): string | null {
  if (!value) return null;
  return /^\/admin\/exams\/[A-Za-z0-9-]+\/[a-z]+$/.test(value) ? value : null;
}

export const withReturnTo = (path: string, returnTo: string) =>
  `${path}${path.includes('?') ? '&' : '?'}returnTo=${encodeURIComponent(returnTo)}`;
