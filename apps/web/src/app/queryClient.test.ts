import { ApiError } from '@/lib/api/problem';
import { shouldRetry } from './queryClient';

describe('shouldRetry', () => {
  it('retries network failures and server errors, at most twice', () => {
    expect(shouldRetry(0, new ApiError(0, 'NETWORK_ERROR', 'offline'))).toBe(true);
    expect(shouldRetry(1, new ApiError(503, 'INTERNAL_ERROR', 'down'))).toBe(true);
    expect(shouldRetry(2, new ApiError(503, 'INTERNAL_ERROR', 'down'))).toBe(false);
  });

  it('never retries answers the server meant, such as 403 or 404', () => {
    expect(shouldRetry(0, new ApiError(403, 'SUBSCRIPTION_REQUIRED', 'locked'))).toBe(false);
    expect(shouldRetry(0, new ApiError(404, 'NOT_FOUND', 'missing'))).toBe(false);
    expect(shouldRetry(0, new Error('bug'))).toBe(false);
  });
});
