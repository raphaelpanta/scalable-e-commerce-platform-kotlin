import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { ProblemError, ThrottledError, UnavailableError } from '@api/problem';
import { createQueryClient, shouldRetry } from '@app/queryClient';

function problemWithStatus(status: number): ProblemError {
  return new ProblemError({ type: 'x', title: 'x', status, errors: [] });
}

describe('shouldRetry (FR-016: never hammer a 4xx, 429 included)', () => {
  it('never retries a 4xx whatever the attempt, and retries other failures up to the limit', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 100, max: 599 }),
        fc.integer({ min: 0, max: 5 }),
        fc.integer({ min: 1, max: 4 }),
        (status, failureCount, maxRetries) => {
          const client = status >= 400 && status < 500;
          const expected = !client && failureCount < maxRetries;
          expect(shouldRetry(failureCount, problemWithStatus(status), maxRetries)).toBe(expected);
        },
      ),
    );
  });

  it('decides on the exact boundaries', () => {
    expect(shouldRetry(0, problemWithStatus(399))).toBe(true);
    expect(shouldRetry(0, problemWithStatus(400))).toBe(false);
    expect(shouldRetry(0, problemWithStatus(499))).toBe(false);
    expect(shouldRetry(0, problemWithStatus(500))).toBe(true);
    expect(shouldRetry(1, problemWithStatus(503))).toBe(true);
    expect(shouldRetry(2, problemWithStatus(503))).toBe(false);
    expect(shouldRetry(3, problemWithStatus(503))).toBe(false);
    const throttled = new ThrottledError(
      { type: 'throttled', title: 'Too many requests', status: 429, errors: [] },
      30,
    );
    expect(shouldRetry(0, throttled)).toBe(false);
    expect(shouldRetry(0, new UnavailableError(undefined, new TypeError('offline')))).toBe(true);
  });

  it('treats anything without a numeric problem status as retryable (within the limit)', () => {
    const cases: unknown[] = [
      null,
      undefined,
      'boom',
      42,
      {},
      { problem: undefined },
      { problem: { status: '400' } },
      new Error('plain'),
    ];
    for (const [index, error] of cases.entries()) {
      expect(shouldRetry(0, error), `case ${index}`).toBe(true);
      expect(shouldRetry(2, error), `case ${index}`).toBe(false);
    }
  });
});

describe('createQueryClient defaults', () => {
  it('uses shouldRetry, no refetch on focus, a 30 s stale time and no mutation retries', () => {
    const defaults = createQueryClient().getDefaultOptions();
    expect(defaults.queries?.retry).toBe(shouldRetry);
    expect(defaults.queries?.refetchOnWindowFocus).toBe(false);
    expect(defaults.queries?.staleTime).toBe(30_000);
    expect(defaults.mutations?.retry).toBe(false);
  });
});
