import * as fc from 'fast-check';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { createApiClient, subscribeUnauthorized } from '@api/client';
import type { paths as GatewayPaths } from '@api/generated/gateway-browser-session';
import {
  ProblemError,
  problemFromBody,
  problemSlug,
  retryAfterSeconds,
  ThrottledError,
  UnauthorizedError,
  UnavailableError,
} from '@api/problem';
import { createCorrelation } from '@app/correlation';
import { isCanonicalUuidV4 } from '@domain/ids';

import { server } from '../msw/server.ts';

const PROBLEM = 'application/problem+json';
const ME = 'http://localhost/api/v1/identity/accounts/me';

function clientWith(correlation = createCorrelation()) {
  return {
    client: createApiClient<GatewayPaths>({ baseUrl: 'http://localhost', correlation }),
    correlation,
  };
}

describe('API client request shape', () => {
  it('sends X-Browser-Session: cookie, a canonical correlation id, same-origin credentials and no token', async () => {
    const seen: Request[] = [];
    server.use(
      http.get(ME, ({ request }) => {
        seen.push(request.clone());
        return HttpResponse.json({
          id: 'x',
          email: 'a@b.io',
          emailVerified: true,
          roles: ['shopper'],
          createdAt: 'now',
        });
      }),
    );
    const { client, correlation } = clientWith();
    const { data } = await client.GET('/api/v1/identity/accounts/me');
    expect(data).toBeDefined();
    const request = seen[0]!;
    expect(request.headers.get('X-Browser-Session')).toBe('cookie');
    expect(request.headers.get('X-Correlation-Id')).toBe(correlation.current().value);
    expect(isCanonicalUuidV4(request.headers.get('X-Correlation-Id') ?? '')).toBe(true);
    expect(request.credentials).toBe('same-origin');
    expect(request.headers.has('Authorization')).toBe(false);
    expect(request.headers.has('Cookie')).toBe(false);
  });

  it('renews the correlation id per user action through correlation.next()', async () => {
    const ids: string[] = [];
    server.use(
      http.get(ME, ({ request }) => {
        ids.push(request.headers.get('X-Correlation-Id') ?? '');
        return HttpResponse.json({});
      }),
    );
    const { client, correlation } = clientWith();
    await client.GET('/api/v1/identity/accounts/me');
    const first = correlation.next();
    await client.GET('/api/v1/identity/accounts/me');
    const second = correlation.next();
    await client.GET('/api/v1/identity/accounts/me');
    expect(ids).toEqual([ids[0], first.value, second.value]);
    expect(new Set(ids).size).toBe(3);
    expect(ids.every(isCanonicalUuidV4)).toBe(true);
  });

  it('never reads document.cookie', async () => {
    const cookieSpy = vi.spyOn(Document.prototype, 'cookie', 'get');
    server.use(http.get(ME, () => HttpResponse.json({})));
    const { client } = clientWith();
    await client.GET('/api/v1/identity/accounts/me');
    expect(cookieSpy).not.toHaveBeenCalled();
  });
});

describe('API client error mapping', () => {
  it('maps problem+json to a typed Problem with the slug, fields and correlation id', async () => {
    server.use(
      http.post('http://localhost/api/v1/identity/sessions', () =>
        HttpResponse.json(
          {
            type: 'https://ecommerce.example/problems/validation',
            title: 'Validation failed',
            detail: 'Two fields are invalid',
            status: 400,
            errors: [
              { field: 'email', message: 'must be an email' },
              { field: 'password', message: 'required' },
              { bogus: 1 },
            ],
          },
          { status: 400, headers: { 'Content-Type': PROBLEM, 'X-Correlation-Id': 'c0ffee' } },
        ),
      ),
    );
    const { client } = clientWith();
    const failure = client.POST('/api/v1/identity/sessions', {
      body: { email: 'a@b.io', password: 'x' },
      headers: { 'X-Browser-Session': 'cookie' },
    });
    await expect(failure).rejects.toBeInstanceOf(ProblemError);
    await failure.catch((error: unknown) => {
      if (!(error instanceof ProblemError)) throw error;
      expect(error.problem).toEqual({
        type: 'validation',
        title: 'Validation failed',
        detail: 'Two fields are invalid',
        status: 400,
        correlationId: 'c0ffee',
        errors: [
          { field: 'email', message: 'must be an email' },
          { field: 'password', message: 'required' },
        ],
      });
    });
  });

  it('maps 429 to Throttled with the Retry-After seconds', async () => {
    server.use(
      http.get(ME, () =>
        HttpResponse.json(
          {
            type: 'https://ecommerce.example/problems/throttled',
            title: 'Too many requests',
            status: 429,
          },
          { status: 429, headers: { 'Content-Type': PROBLEM, 'Retry-After': '17' } },
        ),
      ),
    );
    const { client } = clientWith();
    const failure = client.GET('/api/v1/identity/accounts/me');
    await expect(failure).rejects.toBeInstanceOf(ThrottledError);
    await failure.catch((error: unknown) => {
      if (!(error instanceof ThrottledError)) throw error;
      expect(error.retryAfterSeconds).toBe(17);
      expect(error.problem.type).toBe('throttled');
    });
  });

  it('raises Unauthorized on 401 and notifies the session layer', async () => {
    server.use(
      http.get(ME, () =>
        HttpResponse.json(
          {
            type: 'https://ecommerce.example/problems/unauthorized',
            title: 'Unauthorized',
            status: 401,
          },
          { status: 401, headers: { 'Content-Type': PROBLEM } },
        ),
      ),
    );
    const listener = vi.fn();
    const unsubscribe = subscribeUnauthorized(listener);
    try {
      const { client } = clientWith();
      await expect(client.GET('/api/v1/identity/accounts/me')).rejects.toBeInstanceOf(
        UnauthorizedError,
      );
      expect(listener).toHaveBeenCalledTimes(1);
    } finally {
      unsubscribe();
    }
  });

  it('maps a network failure to Unavailable carrying the correlation id', async () => {
    server.use(http.get(ME, () => HttpResponse.error()));
    const { client, correlation } = clientWith();
    const failure = client.GET('/api/v1/identity/accounts/me');
    await expect(failure).rejects.toBeInstanceOf(UnavailableError);
    await failure.catch((error: unknown) => {
      if (!(error instanceof UnavailableError)) throw error;
      expect(error.problem.correlationId).toBe(correlation.current().value);
      expect(error.problem.type).toBe('unavailable');
    });
  });

  it('falls back to a generic problem when the body is not a problem document', async () => {
    server.use(
      http.get(
        ME,
        () =>
          new HttpResponse('<html>bad gateway</html>', {
            status: 502,
            headers: { 'Content-Type': 'text/html' },
          }),
      ),
    );
    const { client } = clientWith();
    const failure = client.GET('/api/v1/identity/accounts/me');
    await expect(failure).rejects.toBeInstanceOf(ProblemError);
    await failure.catch((error: unknown) => {
      if (!(error instanceof ProblemError)) throw error;
      expect(error.problem).toMatchObject({ type: 'unknown', status: 502, errors: [] });
    });
  });
});

describe('problem helpers', () => {
  it('problemSlug keeps only the last path segment', () => {
    fc.assert(
      fc.property(fc.stringMatching(/^[a-z-]{1,20}$/), (slug) => {
        expect(problemSlug(`https://ecommerce.example/problems/${slug}`)).toBe(slug);
        expect(problemSlug(`https://ecommerce.example/problems/${slug}/`)).toBe(slug);
      }),
    );
    expect(problemSlug(undefined)).toBe('unknown');
    expect(problemSlug('about:blank')).toBe('unknown');
  });

  it('problemFromBody keeps the extension members apart and omits them when there are none', () => {
    const refused = problemFromBody(
      {
        type: 'https://ecommerce.example/problems/price-changed',
        title: 'Price changed',
        status: 409,
        instance: '/api/v1/orders',
        changedLines: [{ lineId: 'l1' }],
        currentCartRevision: 'rev-2',
      },
      409,
      undefined,
    );
    expect(refused.extensions).toEqual({
      changedLines: [{ lineId: 'l1' }],
      currentCartRevision: 'rev-2',
    });
    expect(refused).not.toHaveProperty('instance');
    expect(problemFromBody({ title: 'Plain', status: 400 }, 400, undefined)).not.toHaveProperty(
      'extensions',
    );
  });

  it('retryAfterSeconds reads delta seconds and HTTP dates, never below one second', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 86_400 }), (seconds) => {
        expect(retryAfterSeconds(String(seconds))).toBe(seconds);
      }),
    );
    expect(retryAfterSeconds('0')).toBe(1);
    expect(retryAfterSeconds(null)).toBe(30);
    expect(retryAfterSeconds('garbage')).toBe(30);
    const now = new Date('2026-10-04T10:00:00Z');
    expect(retryAfterSeconds('Sun, 04 Oct 2026 10:00:45 GMT', now)).toBe(45);
    expect(retryAfterSeconds('Sun, 04 Oct 2026 09:00:00 GMT', now)).toBe(1);
  });
});
