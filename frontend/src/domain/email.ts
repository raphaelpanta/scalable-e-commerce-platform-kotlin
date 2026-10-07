import { err, ok, type Result } from './result.ts';

// Deliberately loose (data-model.md §2): the server decides deliverability. The storefront only
// avoids obviously wasted round trips.
export type EmailError = 'empty' | 'too-long' | 'invalid-format';

export type Email = {
  /** The trimmed input, exactly as typed (display keeps the shopper's casing). */
  readonly value: string;
  /** Lower-cased form, used for comparison only. */
  readonly normalized: string;
};

export const EMAIL_MAX_LENGTH = 254;

function hasValidShape(candidate: string): boolean {
  const at = candidate.indexOf('@');
  if (at <= 0 || at !== candidate.lastIndexOf('@')) return false;
  if (/\s/.test(candidate)) return false;
  // An empty domain has no dot, so the dot position alone decides.
  const domain = candidate.slice(at + 1);
  const dot = domain.indexOf('.');
  return dot > 0 && dot < domain.length - 1;
}

export const Email = {
  parse(input: string): Result<Email, EmailError> {
    const value = input.trim();
    if (value.length === 0) return err('empty');
    if (value.length > EMAIL_MAX_LENGTH) return err('too-long');
    if (!hasValidShape(value)) return err('invalid-format');
    return ok({ value, normalized: value.toLowerCase() });
  },
  equals(left: Email, right: Email): boolean {
    return left.normalized === right.normalized;
  },
} as const;
