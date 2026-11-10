import { z } from 'zod';
import type { ScopeType } from '@/features/billing/api';

/**
 * What a plan or a free access campaign covers (one exam, a fixed bundle, or every exam) and how many attempts it
 * gives per exam type. Shared by the plan and campaign dialogs; both forms hold the same field shapes.
 */

export const LIMIT_ROWS = [
  { key: 'practice', label: 'Practice' },
  { key: 'mock', label: 'Mock exams' },
  { key: 'adaptive', label: 'Adaptive exams' },
] as const;
export type LimitKey = (typeof LIMIT_ROWS)[number]['key'];
export type LimitKind = 'UNLIMITED' | 'COUNT' | 'NONE';
export interface LimitValue {
  kind: LimitKind;
  count: string;
}

export const limitSchema = z.object({ kind: z.enum(['UNLIMITED', 'COUNT', 'NONE']), count: z.string() });
export const limitsSchema = z.object({ practice: limitSchema, mock: limitSchema, adaptive: limitSchema });

export const toLimit = (n: number | null): LimitValue =>
  n === null ? { kind: 'UNLIMITED', count: '' } : n === 0 ? { kind: 'NONE', count: '' } : { kind: 'COUNT', count: String(n) };
export const fromLimit = (l: LimitValue): number | null =>
  l.kind === 'UNLIMITED' ? null : l.kind === 'NONE' ? 0 : Number(l.count);

export const UNLIMITED_ALL = {
  practice: { kind: 'UNLIMITED', count: '' },
  mock: { kind: 'UNLIMITED', count: '' },
  adaptive: { kind: 'UNLIMITED', count: '' },
} as const satisfies Record<LimitKey, LimitValue>;

/** Checks shared by both dialogs: the exams chosen fit the scope, counts are numbers, at least one type is included. */
export function checkScopeAndLimits(
  v: { scopeType: ScopeType; examIds: string[]; limits: Record<LimitKey, LimitValue> },
  ctx: z.RefinementCtx,
) {
  if (v.scopeType === 'EXAM' && v.examIds.length !== 1) {
    ctx.addIssue({ code: 'custom', path: ['examIds'], message: 'Choose the exam.' });
  }
  if (v.scopeType === 'BUNDLE' && v.examIds.length < 2) {
    ctx.addIssue({ code: 'custom', path: ['examIds'], message: 'Choose at least 2 exams for a bundle.' });
  }
  for (const { key } of LIMIT_ROWS) {
    const l = v.limits[key];
    if (l.kind === 'COUNT' && !/^\d{1,5}$/.test(l.count.trim())) {
      ctx.addIssue({ code: 'custom', path: ['limits', key, 'count'], message: 'Enter a number of attempts.' });
    } else if (l.kind === 'COUNT' && (Number(l.count) < 1 || Number(l.count) > 10000)) {
      ctx.addIssue({ code: 'custom', path: ['limits', key, 'count'], message: 'Use 1 to 10,000.' });
    }
  }
  if (LIMIT_ROWS.every(({ key }) => v.limits[key].kind === 'NONE')) {
    ctx.addIssue({ code: 'custom', path: ['limits', 'practice', 'kind'], message: 'Include at least one exam type.' });
  }
}
