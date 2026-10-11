import { jsonResponse } from '@/test/render';
import { clearSession, refreshAccessToken } from '@/lib/auth/session';
import { apiFetch } from './client';
import { ApiError } from './problem';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
  clearSession();
});

const unauthorized = () => jsonResponse({ type: 'about:blank', title: 'Sign-in required', status: 401, code: 'UNAUTHENTICATED' }, 401);

describe('apiFetch', () => {
  it('calls the API through the same-origin /api prefix and returns JSON', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 'e1' }));
    await expect(apiFetch('/exams/e1')).resolves.toEqual({ id: 'e1' });
    expect(fetchMock).toHaveBeenCalledWith('/api/exams/e1', expect.objectContaining({ credentials: 'same-origin' }));
  });

  it('builds query strings, skipping undefined values and joining arrays', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));
    await apiFetch('/access/exams', { query: { examIds: ['a', 'b'], page: 0, q: undefined } });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/access/exams?examIds=a%2Cb&page=0');
  });

  it('sends JSON bodies with the right content type', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(apiFetch('/attempts', { method: 'POST', json: { mode: 'PRACTICE' } })).resolves.toBeUndefined();
    const init = fetchMock.mock.calls[0]?.[1];
    expect(init?.body).toBe('{"mode":"PRACTICE"}');
    expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json');
  });

  it('attaches the access token once signed in', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ accessToken: 'tok-1', expiresIn: 900 }));
    await refreshAccessToken();
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true }));
    await apiFetch('/users/me');
    expect(new Headers(fetchMock.mock.calls[1]?.[1]?.headers).get('Authorization')).toBe('Bearer tok-1');
  });

  it('refreshes an expired access token once and retries the request', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ accessToken: 'old', expiresIn: 900 }));
    await refreshAccessToken();
    fetchMock
      .mockResolvedValueOnce(unauthorized())
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'new', expiresIn: 900 }))
      .mockResolvedValueOnce(jsonResponse({ id: 'me' }));
    await expect(apiFetch('/users/me')).resolves.toEqual({ id: 'me' });
    const calls = fetchMock.mock.calls.slice(1);
    expect(calls.map((c) => c[0])).toEqual(['/api/users/me', '/api/auth/token', '/api/users/me']);
    expect(new Headers(calls[2]?.[1]?.headers).get('Authorization')).toBe('Bearer new');
    expect(new Headers(calls[1]?.[1]?.headers).get('X-Requested-With')).toBe('fetch');
  });

  it('reports 401 when the session cannot be renewed', async () => {
    fetchMock.mockResolvedValueOnce(unauthorized()).mockResolvedValueOnce(unauthorized());
    const error = (await apiFetch('/users/me').catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('shares one refresh between concurrent callers', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ accessToken: 'shared', expiresIn: 900 }));
    const [a, b] = await Promise.all([refreshAccessToken(), refreshAccessToken()]);
    expect(a).toBe('shared');
    expect(b).toBe('shared');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('turns a problem response into an ApiError with its stable code', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          type: 'https://leenexlabs.example/problems/subscription-required',
          title: 'Subscription required',
          status: 403,
          code: 'SUBSCRIPTION_REQUIRED',
          detail: 'This exam needs a subscription.',
          correlationId: 'corr-1',
          errors: [{ field: 'planId', message: 'is required' }],
        },
        403,
      ),
    );
    const error = await apiFetch('/attempts').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(403);
    expect(apiError.code).toBe('SUBSCRIPTION_REQUIRED');
    expect(apiError.message).toBe('This exam needs a subscription.');
    expect(apiError.correlationId).toBe('corr-1');
    expect(apiError.fieldError('planId')).toBe('is required');
  });

  it('handles a non-problem error body (e.g. a proxy page)', async () => {
    fetchMock.mockResolvedValue(
      new Response('<html>Bad gateway</html>', { status: 502, headers: { 'X-Correlation-Id': 'corr-2' } }),
    );
    const error = (await apiFetch('/plans').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('INTERNAL_ERROR');
    expect(error.correlationId).toBe('corr-2');
  });

  it('reports network failures as status 0', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const error = (await apiFetch('/plans').catch((e: unknown) => e)) as ApiError;
    expect(error.isNetworkError).toBe(true);
    expect(error.code).toBe('NETWORK_ERROR');
  });
});
