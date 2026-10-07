import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { Address, ADDRESS_BOUNDS, type AddressInput } from '@domain/address';
import { CartRevision } from '@domain/cartRevision';
import { Email, EMAIL_MAX_LENGTH } from '@domain/email';
import { CorrelationId, IdempotencyKey, isCanonicalUuidV4, isUuid } from '@domain/ids';
import { Money } from '@domain/money';
import { Password, PASSWORD_MIN_LENGTH, REDACTED } from '@domain/password';
import { Quantity, QUANTITY_MAX, QUANTITY_MIN } from '@domain/quantity';
import { err, isErr, isOk, mapResult, ok, unwrapOr } from '@domain/result';

const label = fc.string({ minLength: 1, maxLength: 20 }).filter((s) => /^[a-z0-9-]+$/i.test(s));
const emailLike = fc
  .tuple(label, label, label)
  .map(([local, domain, tld]) => `${local}@${domain}.${tld}`);
const whitespace = fc.constantFrom(' ', '\t', '\n', '  ');
const trimmedText = (min: number, max: number) =>
  fc
    .string({ minLength: min, maxLength: max })
    .map((s) => s.trim())
    .filter((s) => s.length >= min && s.length <= max);

describe('Result', () => {
  it('ok and err are distinguishable and mappable', () => {
    fc.assert(
      fc.property(fc.integer(), fc.string(), (value, reason) => {
        const good = ok(value);
        const bad = err(reason);
        expect(isOk(good) && good.value).toBe(value);
        expect(isErr(bad) && bad.reason).toBe(reason);
        expect(
          unwrapOr(
            mapResult(good, (v) => v + 1),
            0,
          ),
        ).toBe(value + 1);
        expect(
          unwrapOr(
            mapResult<number, number, string>(bad, (v) => v + 1),
            -1,
          ),
        ).toBe(-1);
      }),
    );
  });
});

describe('Email', () => {
  it('accepts a well-formed address surrounded by whitespace and trims it', () => {
    fc.assert(
      fc.property(emailLike, whitespace, whitespace, (email, before, after) => {
        const result = Email.parse(`${before}${email}${after}`);
        expect(result.ok).toBe(true);
        if (result.ok) {
          expect(result.value.value).toBe(email);
          expect(result.value.normalized).toBe(email.toLowerCase());
        }
      }),
    );
  });

  it('compares case-insensitively but keeps the typed casing', () => {
    fc.assert(
      fc.property(emailLike, (email) => {
        const lower = Email.parse(email.toLowerCase());
        const upper = Email.parse(email.toUpperCase());
        expect(lower.ok && upper.ok).toBe(true);
        if (lower.ok && upper.ok) {
          expect(Email.equals(lower.value, upper.value)).toBe(true);
          expect(upper.value.value).toBe(email.toUpperCase());
        }
      }),
    );
  });

  it('rejects empty, too long and malformed addresses', () => {
    expect(Email.parse('   ')).toEqual(err('empty'));
    expect(Email.parse(`${'a'.repeat(EMAIL_MAX_LENGTH)}@x.io`)).toEqual(err('too-long'));
    for (const malformed of ['a@', 'a@.', 'a@.io', 'a@io.', 'a@b.io c', '@b.io', 'a@@b.io']) {
      expect(Email.parse(malformed), malformed).toEqual(err('invalid-format'));
    }
    expect(Email.parse(`a@${'b'.repeat(EMAIL_MAX_LENGTH - 7)}.io`).ok).toBe(true);
    fc.assert(
      fc.property(
        fc.oneof(
          fc.string().filter((s) => !s.includes('@') && s.trim().length > 0),
          fc.tuple(label, label).map(([a, b]) => `${a}@${b}`),
          fc.tuple(label, label, label).map(([a, b, c]) => `${a}@${b}@${c}.io`),
          fc.tuple(label, label).map(([a, b]) => `@${a}.${b}`),
          fc.tuple(label, label).map(([a, b]) => `${a}@ ${b}.io`),
          fc.tuple(label, label).map(([a, b]) => `${a}@.${b}`),
          fc.tuple(label, label).map(([a, b]) => `${a}@${b}.`),
        ),
        (bad) => {
          const result = Email.parse(bad);
          expect(result.ok).toBe(false);
          if (!result.ok) expect(['invalid-format', 'too-long']).toContain(result.reason);
        },
      ),
    );
  });

  it('is idempotent on normalisation', () => {
    fc.assert(
      fc.property(emailLike, (email) => {
        const once = Email.parse(email);
        expect(once.ok).toBe(true);
        if (once.ok) expect(Email.parse(once.value.value)).toEqual(once);
      }),
    );
  });
});

describe('Password', () => {
  it('accepts any raw value of at least the minimum length without trimming', () => {
    fc.assert(
      fc.property(fc.string({ minLength: PASSWORD_MIN_LENGTH, maxLength: 128 }), (raw) => {
        const result = Password.create(raw);
        expect(result.ok).toBe(true);
        if (result.ok) {
          expect(result.value.unwrap()).toBe(raw);
          expect(result.value.length).toBe(raw.length);
        }
      }),
    );
  });

  it('rejects short passwords only when a new password is chosen', () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1, maxLength: PASSWORD_MIN_LENGTH - 1 }), (raw) => {
        expect(Password.create(raw)).toEqual(err('too-short'));
        expect(Password.forSignIn(raw).ok).toBe(true);
      }),
    );
    expect(Password.create('')).toEqual(err('empty'));
    expect(Password.forSignIn('')).toEqual(err('empty'));
  });

  it('never renders its value', () => {
    fc.assert(
      fc.property(fc.string({ minLength: PASSWORD_MIN_LENGTH }), (raw) => {
        const result = Password.create(raw);
        if (!result.ok) return;
        const password = result.value;
        expect(String(password)).toBe(REDACTED);
        expect(password.toString()).toBe(REDACTED);
        expect(JSON.stringify({ password })).toBe(JSON.stringify({ password: REDACTED }));
        expect(Object.keys(password)).toEqual([]);
      }),
    );
  });
});

describe('Quantity', () => {
  it('accepts integers in range and rejects everything else', () => {
    fc.assert(
      fc.property(fc.integer({ min: QUANTITY_MIN, max: QUANTITY_MAX }), (n) => {
        expect(Quantity.parse(n)).toEqual(ok({ value: n, kind: 'quantity' }));
      }),
    );
    fc.assert(
      fc.property(fc.integer({ max: QUANTITY_MIN - 1 }), (n) => {
        expect(Quantity.parse(n)).toEqual(err('below-minimum'));
      }),
    );
    fc.assert(
      fc.property(fc.integer({ min: QUANTITY_MAX + 1 }), (n) => {
        expect(Quantity.parse(n)).toEqual(err('above-maximum'));
      }),
    );
    fc.assert(
      fc.property(fc.double({ noInteger: true, noNaN: false }), (n) => {
        expect(Quantity.parse(n)).toEqual(err('not-an-integer'));
      }),
    );
  });

  it('exposes zero only as the remove command', () => {
    expect(Quantity.parse(0)).toEqual(err('below-minimum'));
    expect(Quantity.zero.value).toBe(0);
    expect(Quantity.isRemoval(Quantity.zero)).toBe(true);
    expect(Object.isFrozen(Quantity.zero)).toBe(true);
    const one = Quantity.parse(1);
    if (one.ok) expect(Quantity.isRemoval(one.value)).toBe(false);
  });
});

describe('Money', () => {
  const currency = fc.stringMatching(/^[A-Z]{3}$/);
  const money = fc.record({ amountMinor: fc.nat({ max: Number.MAX_SAFE_INTEGER }), currency });

  it('accepts non-negative safe integer minor units with an ISO 4217 code', () => {
    fc.assert(
      fc.property(money, (input) => {
        expect(Money.parse(input)).toEqual(ok(input));
      }),
    );
  });

  it('rejects negative, fractional, unsafe amounts and malformed currencies', () => {
    fc.assert(
      fc.property(fc.integer({ max: -1 }), currency, (amountMinor, c) => {
        expect(Money.parse({ amountMinor, currency: c })).toEqual(err('amount-negative'));
      }),
    );
    fc.assert(
      fc.property(fc.double({ noInteger: true, min: 0 }), currency, (amountMinor, c) => {
        expect(Money.parse({ amountMinor, currency: c })).toEqual(err('amount-not-integer'));
      }),
    );
    expect(Money.parse({ amountMinor: 2 ** 53, currency: 'BRL' })).toEqual(err('amount-unsafe'));
    fc.assert(
      fc.property(
        fc.nat(),
        fc.string().filter((s) => !/^[A-Z]{3}$/.test(s)),
        (amountMinor, bad) => {
          expect(Money.parse({ amountMinor, currency: bad })).toEqual(err('invalid-currency'));
        },
      ),
    );
  });

  it('formats through Intl with the currency minor-unit digits and supports equality only', () => {
    expect(Money.format({ amountMinor: 123456, currency: 'BRL' }, 'en-US')).toBe('R$1,234.56');
    expect(Money.format({ amountMinor: 123456, currency: 'JPY' }, 'en-US')).toBe('¥123,456');
    expect(Money.format({ amountMinor: 5, currency: 'USD' }, 'en-US')).toBe('$0.05');
    fc.assert(
      fc.property(money, money, (a, b) => {
        expect(Money.equals(a, b)).toBe(
          a.amountMinor === b.amountMinor && a.currency === b.currency,
        );
        expect(Money.equals(a, a)).toBe(true);
      }),
    );
    const exported = Object.keys(Money);
    expect(exported).not.toEqual(
      expect.arrayContaining(['add', 'plus', 'multiply', 'times', 'round']),
    );
  });
});

describe('Address', () => {
  const validInput: fc.Arbitrary<AddressInput> = fc.record(
    {
      recipientName: trimmedText(1, ADDRESS_BOUNDS.recipientName.max),
      line1: trimmedText(1, ADDRESS_BOUNDS.line1.max),
      line2: fc.option(trimmedText(1, ADDRESS_BOUNDS.line2.max), { nil: undefined }),
      city: trimmedText(1, ADDRESS_BOUNDS.city.max),
      region: fc.option(trimmedText(1, ADDRESS_BOUNDS.region.max), { nil: undefined }),
      postalCode: trimmedText(1, ADDRESS_BOUNDS.postalCode.max),
      countryCode: fc.stringMatching(/^[A-Z]{2}$/),
      label: fc.option(trimmedText(1, ADDRESS_BOUNDS.label.max), { nil: undefined }),
      isDefault: fc.option(fc.boolean(), { nil: undefined }),
    },
    { requiredKeys: ['recipientName', 'line1', 'city', 'postalCode', 'countryCode'] },
  );

  it('accepts inputs within the identity bounds, trims them and defaults isDefault to false', () => {
    fc.assert(
      fc.property(validInput, whitespace, (input, pad) => {
        const padded: AddressInput = {
          ...input,
          recipientName: `${pad}${input.recipientName}${pad}`,
          countryCode: `${pad}${input.countryCode}${pad}`,
        };
        const result = Address.parse(padded);
        expect(result.ok).toBe(true);
        if (result.ok) {
          expect(result.value.recipientName).toBe(input.recipientName);
          expect(result.value.countryCode).toBe(input.countryCode);
          expect(result.value.isDefault).toBe(input.isDefault ?? false);
          expect(result.value.line2).toBe(input.line2);
          expect(result.value.region).toBe(input.region);
          expect(result.value.label).toBe(input.label);
        }
      }),
    );
  });

  it('accepts every bounded field at exactly its maximum length', () => {
    const atMax = Address.parse({
      recipientName: 'a'.repeat(ADDRESS_BOUNDS.recipientName.max),
      line1: 'b'.repeat(ADDRESS_BOUNDS.line1.max),
      line2: 'c'.repeat(ADDRESS_BOUNDS.line2.max),
      city: 'd'.repeat(ADDRESS_BOUNDS.city.max),
      region: 'e'.repeat(ADDRESS_BOUNDS.region.max),
      postalCode: 'f'.repeat(ADDRESS_BOUNDS.postalCode.max),
      countryCode: 'BR',
      label: 'g'.repeat(ADDRESS_BOUNDS.label.max),
    });
    expect(atMax.ok).toBe(true);
    if (atMax.ok) expect(atMax.value.label).toBe('g'.repeat(ADDRESS_BOUNDS.label.max));
    const oneOver = Address.parse({
      recipientName: 'a',
      line1: 'b'.repeat(ADDRESS_BOUNDS.line1.max + 1),
      city: 'd',
      postalCode: 'f',
      countryCode: 'BR',
    });
    expect(oneOver).toEqual(err([{ field: 'line1', reason: 'too-long' }]));
  });

  it('rejects out-of-bound fields naming each field once', () => {
    fc.assert(
      fc.property(validInput, (input) => {
        const tooLong = Address.parse({
          ...input,
          recipientName: 'a'.repeat(ADDRESS_BOUNDS.recipientName.max + 1),
          line2: 'b'.repeat(ADDRESS_BOUNDS.line2.max + 1),
          postalCode: 'c'.repeat(ADDRESS_BOUNDS.postalCode.max + 1),
          label: 'd'.repeat(ADDRESS_BOUNDS.label.max + 1),
        });
        expect(tooLong.ok).toBe(false);
        if (!tooLong.ok) {
          expect(tooLong.reason.map((e) => e.field).sort()).toEqual(
            ['label', 'line2', 'postalCode', 'recipientName'].sort(),
          );
          expect(tooLong.reason.every((e) => e.reason === 'too-long')).toBe(true);
        }
        const missing = Address.parse({ ...input, line1: '   ', city: '' });
        expect(missing.ok).toBe(false);
        if (!missing.ok) {
          expect(missing.reason).toEqual(
            expect.arrayContaining([
              { field: 'line1', reason: 'required' },
              { field: 'city', reason: 'required' },
            ]),
          );
          expect(missing.reason).toHaveLength(2);
        }
      }),
    );
  });

  it('requires a two-letter upper-case country code', () => {
    fc.assert(
      fc.property(
        validInput,
        fc.string().filter((s) => !/^[A-Z]{2}$/.test(s.trim())),
        (input, badCountry) => {
          const result = Address.parse({ ...input, countryCode: badCountry });
          expect(result.ok).toBe(false);
          if (!result.ok) {
            expect(result.reason).toHaveLength(1);
            expect(result.reason[0]?.field).toBe('countryCode');
            expect(result.reason[0]?.reason).toBe(
              badCountry.trim() === '' ? 'required' : 'invalid-format',
            );
          }
        },
      ),
    );
  });

  it('parsing is idempotent', () => {
    fc.assert(
      fc.property(validInput, (input) => {
        const once = Address.parse(input);
        expect(once.ok).toBe(true);
        if (once.ok) expect(Address.parse(once.value)).toEqual(once);
      }),
    );
  });
});

describe('CartRevision', () => {
  it('is opaque and compared for equality only', () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1 }), fc.string({ minLength: 1 }), (a, b) => {
        const left = CartRevision.parse(a);
        const right = CartRevision.parse(b);
        expect(left.ok && right.ok).toBe(true);
        if (left.ok && right.ok) expect(CartRevision.equals(left.value, right.value)).toBe(a === b);
      }),
    );
    expect(CartRevision.parse('')).toEqual(err('empty'));
  });
});

describe('CorrelationId and IdempotencyKey', () => {
  const canonicalV4 = fc.uuid({ version: 4 }).map((u) => u.toLowerCase());

  it('accept canonical lower-case UUID v4 only', () => {
    fc.assert(
      fc.property(canonicalV4, (uuid) => {
        expect(isCanonicalUuidV4(uuid)).toBe(true);
        expect(CorrelationId.parse(uuid)).toEqual(ok({ value: uuid, kind: 'correlation-id' }));
        expect(IdempotencyKey.parse(uuid)).toEqual(ok({ value: uuid, kind: 'idempotency-key' }));
        expect(CorrelationId.parse(uuid.toUpperCase()).ok).toBe(uuid === uuid.toUpperCase());
      }),
    );
    fc.assert(
      fc.property(
        fc.oneof(
          fc.uuid({ version: [1, 3, 5, 7] }),
          fc.string().filter((s) => !isCanonicalUuidV4(s)),
          fc.uuid({ version: 4 }).map((u) => u.replace(/-/g, '')),
        ),
        (bad) => {
          expect(CorrelationId.parse(bad)).toEqual(err('not-canonical-uuid-v4'));
          expect(IdempotencyKey.parse(bad)).toEqual(err('not-canonical-uuid-v4'));
        },
      ),
    );
  });

  it('generate lower-cases what the source returns and still validates the shape', () => {
    fc.assert(
      fc.property(fc.uuid({ version: 4 }), (uuid) => {
        const upper = uuid.toUpperCase();
        expect(CorrelationId.generate(() => upper)).toEqual(
          ok({ value: uuid.toLowerCase(), kind: 'correlation-id' }),
        );
        expect(IdempotencyKey.generate(() => upper)).toEqual(
          ok({ value: uuid.toLowerCase(), kind: 'idempotency-key' }),
        );
      }),
    );
    expect(CorrelationId.generate(() => 'not-a-uuid')).toEqual(err('not-canonical-uuid-v4'));
  });

  it('isUuid accepts platform ids of any version and case', () => {
    fc.assert(
      fc.property(fc.uuid(), fc.boolean(), (uuid, upper) => {
        expect(isUuid(upper ? uuid.toUpperCase() : uuid)).toBe(true);
      }),
    );
    expect(isUuid('0b4e6d1c')).toBe(false);
    expect(isUuid('')).toBe(false);
  });
});
