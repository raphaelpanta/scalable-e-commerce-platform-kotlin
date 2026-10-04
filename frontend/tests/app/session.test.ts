import { QueryClient } from '@tanstack/react-query';
import * as fc from 'fast-check';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { createApiClient } from '@api/client';
import type { paths as IdentityPaths } from '@api/generated/identity';
import { UnauthorizedError } from '@api/problem';
import { createSessionPort } from '@api/session';
import { createCorrelation } from '@app/correlation';
import {
  ANONYMOUS,
  createSessionStore,
  hasRole,
  parseRoles,
  reduceSession,
  SESSION_QUERY_KEY,
  SESSION_WARNING_LEAD_MS,
  sessionEndsSoon,
  type SessionEvent,
  type SessionSummary,
  warningAt,
} from '@app/session/sessionStore';
import { Email } from '@domain/email';
import { Password } from '@domain/password';
import { ROLES } from '@domain/status';

import { server } from '../msw/server.ts';

const BASE = 'http://localhost';
const ME = `${BASE}/api/v1/identity/accounts/me`;
const SESSIONS = `${BASE}/api/v1/identity/sessions`;
const CURRENT_SESSION = `${BASE}/api/v1/identity/sessions/current`;
const PROBLEM = { 'Content-Type': 'application/problem+json' };

const role = fc.constantFrom(...ROLES);
const roles = fc.uniqueArray(role, { maxLength: 2 });
const rawRoles = fc.array(fc.oneof(role, fc.string()), { maxLength: 5 });
const instant = fc.date({
  min: new Date('2000-01-01T00:00:00Z'),
  max: new Date('2100-01-01T00:00:00Z'),
  noInvalidDate: true,
});
const summary: fc.Arbitrary<SessionSummary> = fc.oneof(
  fc.constant(ANONYMOUS),
  fc.record({
    state: fc.constant('signedIn' as const),
    expiresAt: fc.option(instant, { nil: undefined }),
    roles,
  }),
);
const endingEvent: fc.Arbitrary<SessionEvent> = fc.constantFrom(
  { type: 'signedOut' },
  { type: 'unauthorized' },
  { type: 'idle' },
);

function unauthorized() {
  return HttpResponse.json(
    { type: 'https://ecommerce.example/problems/unauthorized', title: 'Unauthorized', status: 401 },
    { status: 401, headers: PROBLEM },
  );
}

function account(rolesOfAccount: readonly string[]) {
  return HttpResponse.json({
    id: '0b4e6d1c-0000-4000-8000-000000000001',
    email: 'ana@example.com',
    emailVerified: true,
    roles: rolesOfAccount,
    createdAt: '2026-10-04T10:00:00Z',
  });
}

function newClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function storeFor(queryClient: QueryClient) {
  const correlation = createCorrelation();
  const port = createSessionPort({ baseUrl: BASE, correlation });
  const store = createSessionStore(queryClient, port);
  return { store, correlation };
}

const email = (() => {
  const parsed = Email.parse('ana@example.com');
  if (!parsed.ok) throw new Error('fixture');
  return parsed.value;
})();
const password = (() => {
  const parsed = Password.forSignIn('S3cure-passphrase!');
  if (!parsed.ok) throw new Error('fixture');
  return parsed.value;
})();

describe('session state machine (data-model.md §3.1)', () => {
  it('anonymous becomes signedIn on {expiresAt, roles}; roles are filtered to the known ones', () => {
    fc.assert(
      fc.property(summary, instant, rawRoles, (from, expiresAt, raw) => {
        const next = reduceSession(from, {
          type: 'signedIn',
          summary: { expiresAt: expiresAt.toISOString(), roles: raw },
        });
        expect(next).toEqual({ state: 'signedIn', expiresAt, roles: parseRoles(raw) });
        if (next.state === 'signedIn') {
          expect(next.roles.every((r) => (ROLES as readonly string[]).includes(r))).toBe(true);
        }
      }),
    );
  });

  it('sign-out, any 401 and idle expiry lead to anonymous from every state', () => {
    fc.assert(
      fc.property(summary, endingEvent, (from, event) => {
        expect(reduceSession(from, event)).toBe(ANONYMOUS);
      }),
    );
  });

  it('the page-load probe resolves 200 to signedIn (expiresAt unknown) and 401 to anonymous', () => {
    fc.assert(
      fc.property(summary, rawRoles, (from, raw) => {
        expect(
          reduceSession(from, { type: 'probed', result: { kind: 'signedIn', roles: raw } }),
        ).toEqual({
          state: 'signedIn',
          expiresAt: undefined,
          roles: parseRoles(raw),
        });
        expect(reduceSession(from, { type: 'probed', result: { kind: 'anonymous' } })).toBe(
          ANONYMOUS,
        );
      }),
    );
  });

  it('a renewal moves expiresAt only while signed in', () => {
    fc.assert(
      fc.property(summary, instant, (from, expiresAt) => {
        const next = reduceSession(from, { type: 'renewed', expiresAt: expiresAt.toISOString() });
        if (from.state === 'anonymous') expect(next).toBe(ANONYMOUS);
        else expect(next).toEqual({ ...from, expiresAt });
      }),
    );
  });

  it('an unparsable expiresAt is a missing hint, never an error', () => {
    fc.assert(
      fc.property(
        fc.string().filter((s) => Number.isNaN(Date.parse(s))),
        (garbage) => {
          const next = reduceSession(ANONYMOUS, {
            type: 'signedIn',
            summary: { expiresAt: garbage, roles: [] },
          });
          expect(next).toEqual({ state: 'signedIn', expiresAt: undefined, roles: [] });
        },
      ),
    );
  });

  it('roles gate console links only: hasRole is false whenever anonymous', () => {
    fc.assert(
      fc.property(summary, role, (s, r) => {
        expect(hasRole(s, r)).toBe(s.state === 'signedIn' && s.roles.includes(r));
      }),
    );
  });

  it('the summary never carries a token, account id or email', () => {
    fc.assert(
      fc.property(summary, (s) => {
        expect(Object.keys(s).sort()).toEqual(
          s.state === 'anonymous' ? ['state'] : ['expiresAt', 'roles', 'state'],
        );
      }),
    );
  });
});

describe('"session about to end" warning', () => {
  it('starts exactly at expiresAt − 2 min and only while signed in with a known expiry', () => {
    expect(SESSION_WARNING_LEAD_MS).toBe(120_000);
    fc.assert(
      fc.property(
        instant,
        fc.integer({ min: -3_600_000, max: 3_600_000 }),
        roles,
        (expiresAt, offsetMs, r) => {
          const now = new Date(expiresAt.getTime() + offsetMs);
          const signedIn: SessionSummary = { state: 'signedIn', expiresAt, roles: r };
          expect(sessionEndsSoon(signedIn, now)).toBe(offsetMs >= -SESSION_WARNING_LEAD_MS);
          expect(warningAt(expiresAt).getTime()).toBe(
            expiresAt.getTime() - SESSION_WARNING_LEAD_MS,
          );
          expect(sessionEndsSoon({ state: 'signedIn', expiresAt: undefined, roles: r }, now)).toBe(
            false,
          );
          expect(sessionEndsSoon(ANONYMOUS, now)).toBe(false);
        },
      ),
    );
  });
});

describe('session store over the gateway (MSW)', () => {
  it('probes GET /accounts/me on page load: 200 is signedIn with the account roles', async () => {
    server.use(http.get(ME, () => account(['shopper', 'operator'])));
    const queryClient = newClient();
    const { store } = storeFor(queryClient);
    const resolved = await queryClient.query(store.probeQuery());
    expect(resolved).toEqual({
      state: 'signedIn',
      expiresAt: undefined,
      roles: ['shopper', 'operator'],
    });
    expect(store.current()).toEqual(resolved);
    store.dispose();
  });

  it('a 401 on the probe is anonymous, not an error', async () => {
    server.use(http.get(ME, () => unauthorized()));
    const queryClient = newClient();
    const { store } = storeFor(queryClient);
    await expect(queryClient.query(store.probeQuery())).resolves.toEqual(ANONYMOUS);
    store.dispose();
  });

  it('signs in with X-Browser-Session: cookie and keeps only {expiresAt, roles}', async () => {
    let bodySeen: unknown;
    let headerSeen: string | null = null;
    server.use(
      http.post(SESSIONS, async ({ request }) => {
        bodySeen = await request.json();
        headerSeen = request.headers.get('X-Browser-Session');
        return HttpResponse.json({ expiresAt: '2026-10-04T10:30:00Z', roles: ['shopper'] });
      }),
    );
    const queryClient = newClient();
    const { store } = storeFor(queryClient);
    const next = await store.signIn(email, password);
    expect(headerSeen).toBe('cookie');
    expect(bodySeen).toEqual({ email: 'ana@example.com', password: 'S3cure-passphrase!' });
    expect(next).toEqual({
      state: 'signedIn',
      expiresAt: new Date('2026-10-04T10:30:00Z'),
      roles: ['shopper'],
    });
    expect(queryClient.getQueryData(SESSION_QUERY_KEY)).toEqual(next);
    store.dispose();
  });

  it('sign-out deletes the session, clears the query cache and returns to anonymous', async () => {
    let deleted = 0;
    server.use(
      http.post(SESSIONS, () =>
        HttpResponse.json({ expiresAt: '2026-10-04T10:30:00Z', roles: ['shopper'] }),
      ),
      http.delete(CURRENT_SESSION, () => {
        deleted += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const queryClient = newClient();
    const { store } = storeFor(queryClient);
    await store.signIn(email, password);
    queryClient.setQueryData(['orders', 'mine'], [{ id: 'o1' }]);
    await store.signOut();
    expect(deleted).toBe(1);
    expect(queryClient.getQueryData(['orders', 'mine'])).toBeUndefined();
    expect(store.current()).toEqual(ANONYMOUS);
    store.dispose();
  });

  it('any 401 while signed in clears the cache and resets to anonymous', async () => {
    server.use(
      http.post(SESSIONS, () =>
        HttpResponse.json({ expiresAt: '2026-10-04T10:30:00Z', roles: ['shopper'] }),
      ),
      http.get(ME, () => unauthorized()),
    );
    const queryClient = newClient();
    const { store, correlation } = storeFor(queryClient);
    await store.signIn(email, password);
    queryClient.setQueryData(['cart'], { lines: [] });
    const identity = createApiClient<IdentityPaths>({ baseUrl: BASE, correlation });
    await expect(identity.GET('/api/v1/identity/accounts/me')).rejects.toBeInstanceOf(
      UnauthorizedError,
    );
    expect(queryClient.getQueryData(['cart'])).toBeUndefined();
    expect(store.current()).toEqual(ANONYMOUS);
    store.dispose();
  });

  it('a 401 while already anonymous leaves public cached data alone', async () => {
    server.use(http.get(ME, () => unauthorized()));
    const queryClient = newClient();
    const { store, correlation } = storeFor(queryClient);
    queryClient.setQueryData(['products'], [{ id: 'p1' }]);
    const identity = createApiClient<IdentityPaths>({ baseUrl: BASE, correlation });
    await expect(identity.GET('/api/v1/identity/accounts/me')).rejects.toBeInstanceOf(
      UnauthorizedError,
    );
    expect(queryClient.getQueryData(['products'])).toEqual([{ id: 'p1' }]);
    expect(store.current()).toEqual(ANONYMOUS);
    store.dispose();
  });

  it('after dispose the store no longer reacts to 401s', async () => {
    server.use(
      http.post(SESSIONS, () =>
        HttpResponse.json({ expiresAt: '2026-10-04T10:30:00Z', roles: ['shopper'] }),
      ),
      http.get(ME, () => unauthorized()),
    );
    const queryClient = newClient();
    const { store, correlation } = storeFor(queryClient);
    await store.signIn(email, password);
    store.dispose();
    const identity = createApiClient<IdentityPaths>({ baseUrl: BASE, correlation });
    await identity.GET('/api/v1/identity/accounts/me').catch(() => undefined);
    expect(store.current().state).toBe('signedIn');
  });
});
