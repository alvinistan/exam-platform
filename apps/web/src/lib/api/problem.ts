/** Error codes every service may return (contracts/openapi/common.yaml → Problem.code). */
export type ProblemCode =
  | 'VALIDATION_FAILED'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'ACCOUNT_SUSPENDED'
  | 'SUBSCRIPTION_REQUIRED'
  | 'SUBSCRIPTION_EXPIRED'
  | 'PAYMENT_PENDING'
  | 'FREE_ALLOWANCE_USED'
  | 'MODE_DISABLED'
  | 'ATTEMPT_EXPIRED'
  | 'ATTEMPT_FINISHED'
  | 'INSUFFICIENT_QUESTIONS'
  | 'SLUG_TAKEN'
  | 'PUBLISH_BLOCKED'
  | 'RATE_LIMITED'
  | 'METHOD_NOT_ALLOWED'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'INTERNAL_ERROR';

export interface Problem {
  type: string;
  title: string;
  status: number;
  code: ProblemCode | (string & {});
  detail?: string;
  correlationId?: string;
  errors?: { field: string; message: string }[];
}

/** Thrown by the API client for any non-2xx response, and for network failures (status 0). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: Problem | undefined;
  readonly correlationId: string | undefined;

  constructor(status: number, code: string, message: string, problem?: Problem, correlationId?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.problem = problem;
    this.correlationId = problem?.correlationId ?? correlationId;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }

  /** Message for one form field, from the problem's field errors. */
  fieldError(field: string): string | undefined {
    return this.problem?.errors?.find((e) => e.field === field)?.message;
  }
}

export function isProblem(value: unknown): value is Problem {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Record<string, unknown>;
  return typeof v.status === 'number' && typeof v.code === 'string' && typeof v.title === 'string';
}
