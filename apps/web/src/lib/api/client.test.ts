import { jsonResponse } from '@/test/render';
import { apiFetch } from './client';
import { ApiError } from './problem';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  fetchMock.mockReset();
});

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
