import * as fc from 'fast-check';
import { describe, expect, it, vi } from 'vitest';

import { isCanonicalUuidV4 } from '@domain/ids';
import { SESSION_ID_KEY, sessionId, type SessionStorageLike } from '@telemetry/sessionId';

import { cookie, jwt } from './support.ts';

// T092: the telemetry session id is a random UUID kept in sessionStorage, distinct from any
// credential, and localStorage is never touched (data-model.md §3.5, constitution VII).
function memoryStorage(initial: Record<string, string> = {}): SessionStorageLike & {
  values: Map<string, string>;
} {
  const values = new Map(Object.entries(initial));
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value);
    },
  };
}

describe('the telemetry session id', () => {
  it('is a random canonical UUID v4 stored under one sessionStorage key', () => {
    const storage = memoryStorage();
    const id = sessionId(storage, () => globalThis.crypto.randomUUID());
    expect(isCanonicalUuidV4(id)).toBe(true);
    expect([...storage.values.entries()]).toEqual([[SESSION_ID_KEY, id]]);
  });

  it('is the same for the whole tab session and different for another one', () => {
    fc.assert(
      fc.property(fc.uuid({ version: 4 }), fc.uuid({ version: 4 }), (first, second) => {
        fc.pre(first !== second);
        const storage = memoryStorage();
        expect(sessionId(storage, () => first)).toBe(first);
        expect(sessionId(storage, () => second)).toBe(first);
        expect(sessionId(memoryStorage(), () => second)).toBe(second);
      }),
    );
  });

  it('replaces a stored value that is not a UUID (never reuses anything else kept there)', () => {
    fc.assert(
      fc.property(fc.oneof(jwt, cookie, fc.string()), (stored) => {
        const storage = memoryStorage({ [SESSION_ID_KEY]: stored });
        const id = sessionId(storage, () => globalThis.crypto.randomUUID());
        expect(isCanonicalUuidV4(id)).toBe(true);
        expect(id).not.toBe(stored);
        expect(storage.values.get(SESSION_ID_KEY)).toBe(id);
      }),
    );
  });

  it('is never derived from the session cookie or any credential', () => {
    document.cookie = 'session=k1.AAAAAAAAAAAAAAAA_session-example; path=/';
    window.sessionStorage.setItem('storefront.checkout.draft', '{"idempotencyKey":"x"}');
    const id = sessionId(window.sessionStorage, () => globalThis.crypto.randomUUID());
    expect(document.cookie).not.toContain(id);
    expect(id).not.toContain('session');
    expect(id).not.toContain('k1.');
    document.cookie = 'session=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('survives storage that refuses to read or write, and a source that returns garbage', () => {
    const blocked: SessionStorageLike = {
      getItem: () => {
        throw new DOMException('blocked', 'SecurityError');
      },
      setItem: () => {
        throw new DOMException('full', 'QuotaExceededError');
      },
    };
    const fixed = '7b8e1a54-2f0c-4d6a-9a3e-5c1f0d2b8e77';
    expect(sessionId(blocked, () => fixed)).toBe(fixed);
    expect(isCanonicalUuidV4(sessionId(undefined, () => 'not a uuid'))).toBe(true);
    expect(sessionId(undefined, () => fixed.toUpperCase())).toBe(fixed);
  });

  it('never touches localStorage', () => {
    const local = vi.spyOn(window, 'localStorage', 'get');
    sessionId(window.sessionStorage, () => globalThis.crypto.randomUUID());
    sessionId(window.sessionStorage, () => globalThis.crypto.randomUUID());
    expect(local).not.toHaveBeenCalled();
  });
});
