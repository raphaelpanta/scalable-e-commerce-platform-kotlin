import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import type { SessionSummary } from '@app/session/sessionStore';
import { signInAndMerge } from '@app/session/signIn';
import { Email } from '@domain/email';
import { Password } from '@domain/password';

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
const signedIn: SessionSummary = { state: 'signedIn', expiresAt: undefined, roles: ['shopper'] };

const capped = fc.array(
  fc.record({
    productId: fc.uuid(),
    requestedQuantity: fc.integer({ min: 1, max: 200 }),
    appliedQuantity: fc.integer({ min: 0, max: 99 }),
  }),
  { maxLength: 3 },
);

describe('sign-in then merge (storefront-routes.md redirect rule, data-model.md §3.1)', () => {
  it('signs in first, merges second and reports the capped lines', async () => {
    await fc.assert(
      fc.asyncProperty(fc.option(capped, { nil: null }), async (notice) => {
        const calls: string[] = [];
        const outcome = await signInAndMerge(
          {
            sessionStore: {
              signIn: () => {
                calls.push('signIn');
                return Promise.resolve(signedIn);
              },
            },
            cart: {
              merge: () => {
                calls.push('merge');
                return Promise.resolve(notice);
              },
            },
          },
          email,
          password,
        );
        expect(calls).toEqual(['signIn', 'merge']);
        expect(outcome.summary).toBe(signedIn);
        expect(outcome.mergeNotice).toEqual(notice);
      }),
    );
  });

  it('a refused sign-in merges nothing and propagates the refusal', async () => {
    const calls: string[] = [];
    await expect(
      signInAndMerge(
        {
          sessionStore: { signIn: () => Promise.reject(new Error('401')) },
          cart: {
            merge: () => {
              calls.push('merge');
              return Promise.resolve(null);
            },
          },
        },
        email,
        password,
      ),
    ).rejects.toThrow('401');
    expect(calls).toEqual([]);
  });

  it('a merge that fails (in progress, outage) never blocks the sign-in', async () => {
    const outcome = await signInAndMerge(
      {
        sessionStore: { signIn: () => Promise.resolve(signedIn) },
        cart: { merge: () => Promise.reject(new Error('409 merge in progress')) },
      },
      email,
      password,
    );
    expect(outcome.summary).toBe(signedIn);
    expect(outcome.mergeNotice).toBeNull();
  });
});
