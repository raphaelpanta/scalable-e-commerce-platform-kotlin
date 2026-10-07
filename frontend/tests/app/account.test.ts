import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { deleteAccount } from '@app/identity/deleteAccount';
import type { NotificationPreferences } from '@app/identity/identityPort';
import {
  canSave,
  changed,
  channelsOf,
  type ChannelChoice,
  choiceOf,
  smsAvailable,
} from '@app/identity/preferences';
import { Password } from '@domain/password';

const choice: fc.Arbitrary<ChannelChoice> = fc.record({ email: fc.boolean(), sms: fc.boolean() });
const preferences: fc.Arbitrary<NotificationPreferences> = fc.record({
  channels: fc.subarray(['email', 'sms'] as const),
  phoneVerified: fc.boolean(),
});

describe('notification preference rules', () => {
  it('sends the chosen channels in the fixed order, email then sms', () => {
    fc.assert(
      fc.property(choice, (picked) => {
        const channels = channelsOf(picked);
        expect(channels.includes('email')).toBe(picked.email);
        expect(channels.includes('sms')).toBe(picked.sms);
        expect(channels).toHaveLength(Number(picked.email) + Number(picked.sms));
        if (picked.email && picked.sms) expect(channels).toEqual(['email', 'sms']);
      }),
    );
    expect(channelsOf({ email: true, sms: false })).toEqual(['email']);
    expect(channelsOf({ email: false, sms: true })).toEqual(['sms']);
    expect(channelsOf({ email: false, sms: false })).toEqual([]);
  });

  it('reads the stored channels back as the same choice', () => {
    fc.assert(
      fc.property(preferences, (stored) => {
        expect(choiceOf(stored)).toEqual({
          email: stored.channels.includes('email'),
          sms: stored.channels.includes('sms'),
        });
        expect(changed(stored, choiceOf(stored))).toBe(false);
      }),
    );
  });

  it('can be saved only with at least one channel on', () => {
    fc.assert(
      fc.property(choice, (picked) => {
        expect(canSave(picked)).toBe(picked.email || picked.sms);
      }),
    );
  });

  it('offers sms only with a verified phone number', () => {
    fc.assert(
      fc.property(preferences, (stored) => {
        expect(smsAvailable(stored)).toBe(stored.phoneVerified);
      }),
    );
  });

  it('detects a changed channel in either direction', () => {
    const stored: NotificationPreferences = { channels: ['email'], phoneVerified: true };
    expect(changed(stored, { email: true, sms: true })).toBe(true);
    expect(changed(stored, { email: false, sms: false })).toBe(true);
    expect(changed(stored, { email: true, sms: false })).toBe(false);
  });
});

describe('account deletion', () => {
  const password = (() => {
    const parsed = Password.forSignIn('S3cure-passphrase!');
    if (!parsed.ok) throw new Error('fixture');
    return parsed.value;
  })();

  function fakes(rightPassword: boolean, signOutFails = false) {
    const calls: string[] = [];
    return {
      calls,
      identity: {
        confirmPassword: (email: string) => {
          calls.push(`confirm ${email}`);
          return Promise.resolve(rightPassword);
        },
        deleteOwnAccount: () => {
          calls.push('delete');
          return Promise.resolve();
        },
      },
      sessionStore: {
        signOut: () => {
          calls.push('signOut');
          return signOutFails ? Promise.reject(new Error('no session')) : Promise.resolve();
        },
      },
    };
  }

  it('proves the password, deletes the account and signs out, in that order', async () => {
    const dependencies = fakes(true);
    await expect(deleteAccount(dependencies, 'ana@example.com', password)).resolves.toBe('deleted');
    expect(dependencies.calls).toEqual(['confirm ana@example.com', 'delete', 'signOut']);
  });

  it('deletes nothing and keeps the session when the password is wrong', async () => {
    const dependencies = fakes(false);
    await expect(deleteAccount(dependencies, 'ana@example.com', password)).resolves.toBe(
      'wrongPassword',
    );
    expect(dependencies.calls).toEqual(['confirm ana@example.com']);
  });

  it('still reports the deletion when the sign-out finds no session any more', async () => {
    const dependencies = fakes(true, true);
    await expect(deleteAccount(dependencies, 'ana@example.com', password)).resolves.toBe('deleted');
    expect(dependencies.calls).toEqual(['confirm ana@example.com', 'delete', 'signOut']);
  });
});
