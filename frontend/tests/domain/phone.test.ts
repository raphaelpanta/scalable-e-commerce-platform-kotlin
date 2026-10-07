import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { PhoneNumber, VerificationCode } from '@domain/phone';

const digits = (min: number, max: number) =>
  fc
    .array(fc.integer({ min: 0, max: 9 }), { minLength: min, maxLength: max })
    .map((d) => d.join(''));

describe('PhoneNumber (E.164, identity PhoneVerificationRequest)', () => {
  it('accepts a plus, a non-zero first digit and 8 to 15 digits in total', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: 9 }), digits(7, 14), (first, rest) => {
        const parsed = PhoneNumber.parse(`+${String(first)}${rest}`);
        expect(parsed.ok).toBe(true);
        if (parsed.ok) expect(parsed.value.value).toBe(`+${String(first)}${rest}`);
      }),
    );
  });

  it('rejects a number that is too short, too long, starts with 0 or lacks the plus', () => {
    expect(PhoneNumber.parse('+1234567')).toEqual({ ok: false, reason: 'invalid-format' });
    expect(PhoneNumber.parse('+1234567890123456')).toEqual({ ok: false, reason: 'invalid-format' });
    expect(PhoneNumber.parse('+0123456789')).toEqual({ ok: false, reason: 'invalid-format' });
    expect(PhoneNumber.parse('351912345678')).toEqual({ ok: false, reason: 'invalid-format' });
    expect(PhoneNumber.parse('+35191234567a')).toEqual({ ok: false, reason: 'invalid-format' });
    expect(PhoneNumber.parse('+12345678').ok).toBe(true);
    expect(PhoneNumber.parse('+123456789012345').ok).toBe(true);
  });

  it('drops the spaces and hyphens typed between digits and trims the ends', () => {
    const parsed = PhoneNumber.parse('  +351 912-345 678  ');
    expect(parsed).toMatchObject({ ok: true, value: { value: '+351912345678' } });
    expect(PhoneNumber.parse('   ')).toEqual({ ok: false, reason: 'empty' });
    expect(PhoneNumber.parse('')).toEqual({ ok: false, reason: 'empty' });
  });
});

describe('VerificationCode (six digits)', () => {
  it('accepts exactly six digits, trimmed', () => {
    fc.assert(
      fc.property(digits(6, 6), (code) => {
        expect(VerificationCode.parse(` ${code} `)).toMatchObject({
          ok: true,
          value: { value: code },
        });
      }),
    );
  });

  it('rejects anything else, and says when it is empty', () => {
    for (const bad of ['12345', '1234567', '12345a', '12 345', '１２３４５６']) {
      expect(VerificationCode.parse(bad)).toEqual({ ok: false, reason: 'invalid-format' });
    }
    expect(VerificationCode.parse('  ')).toEqual({ ok: false, reason: 'empty' });
  });
});
