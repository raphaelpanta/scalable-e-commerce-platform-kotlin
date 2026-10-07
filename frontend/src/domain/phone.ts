import { err, ok, type Result } from './result.ts';

// The phone number and the verification code of the notification preferences (identity's
// `PhoneVerificationRequest` and `PhoneVerificationConfirmation`): an E.164 number and a six digit
// code. The storefront only avoids obviously wasted round trips; identity decides.
export type PhoneNumberError = 'empty' | 'invalid-format';
export type VerificationCodeError = 'empty' | 'invalid-format';

const E164 = /^\+[1-9][0-9]{7,14}$/;
const SEPARATORS = /[\s-]/g;
const SIX_DIGITS = /^[0-9]{6}$/;

export type PhoneNumber = { readonly value: string; readonly kind: 'phone-number' };
export type VerificationCode = { readonly value: string; readonly kind: 'verification-code' };

export const PhoneNumber = {
  /** An international number; spaces and hyphens typed between the digits are dropped. */
  parse(input: string): Result<PhoneNumber, PhoneNumberError> {
    const trimmed = input.trim();
    if (trimmed.length === 0) return err('empty');
    const value = trimmed.replace(SEPARATORS, '');
    return E164.test(value) ? ok({ value, kind: 'phone-number' }) : err('invalid-format');
  },
} as const;

export const VerificationCode = {
  parse(input: string): Result<VerificationCode, VerificationCodeError> {
    const value = input.trim();
    if (value.length === 0) return err('empty');
    return SIX_DIGITS.test(value)
      ? ok({ value, kind: 'verification-code' })
      : err('invalid-format');
  },
} as const;
