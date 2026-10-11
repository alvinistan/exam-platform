import { getAccessToken, refreshAccessToken } from '@/lib/auth/session';
import { ApiError, isProblem } from './problem';

export interface RequestOptions extends Omit<RequestInit, 'body'> {
  /** JSON-serialised as the request body. */
  json?: unknown;
  /** Query parameters; undefined values are skipped, arrays become comma-separated. */
  query?: Record<string, string | number | boolean | readonly (string | number)[] | undefined>;
}

const BASE = '/api';

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = path.startsWith('/') ? `${BASE}${path}` : `${BASE}/${path}`;
  if (!query) return url;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined) continue;
    params.set(key, Array.isArray(value) ? value.join(',') : String(value));
  }
  const qs = params.toString();
  return qs ? `${url}?${qs}` : url;
}

/**
 * Typed fetch wrapper for the platform API (always same-origin, through the gateway).
 * Non-2xx responses become {@link ApiError} carrying the problem's stable `code`.
 *
 * The access token is attached automatically. If the server answers 401 (token expired), the token is refreshed
 * once and the request retried, so callers never handle expiry themselves.
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let token = getAccessToken();
  let response = await send(path, options, token);
  if (response.status === 401) {
    const renewed = await refreshAccessToken();
    if (renewed && renewed !== token) {
      token = renewed;
      response = await send(path, options, token);
    }
  }
  return parse<T>(response);
}

async function send(path: string, options: RequestOptions, token: string | null): Promise<Response> {
  const { json, query, headers, ...init } = options;
  const finalHeaders = new Headers(headers);
  finalHeaders.set('Accept', 'application/json, application/problem+json');
  if (json !== undefined) finalHeaders.set('Content-Type', 'application/json');
  if (token) finalHeaders.set('Authorization', `Bearer ${token}`);

  try {
    return await fetch(buildUrl(path, query), {
      ...init,
      headers: finalHeaders,
      credentials: 'same-origin',
      ...(json !== undefined ? { body: JSON.stringify(json) } : {}),
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server. Check your connection and try again.');
  }
}

async function parse<T>(response: Response): Promise<T> {
  const correlationId = response.headers.get('X-Correlation-Id') ?? undefined;
  if (response.status === 204) return undefined as T;

  const isJson = /\bjson\b/.test(response.headers.get('Content-Type') ?? '');
  const body: unknown = isJson ? await response.json().catch(() => undefined) : undefined;

  if (!response.ok) {
    if (isProblem(body)) {
      throw new ApiError(response.status, body.code, body.detail ?? body.title, body, correlationId);
    }
    throw new ApiError(
      response.status,
      response.status >= 500 ? 'INTERNAL_ERROR' : 'UNEXPECTED_RESPONSE',
      `Request failed with status ${response.status}.`,
      undefined,
      correlationId,
    );
  }
  return body as T;
}
