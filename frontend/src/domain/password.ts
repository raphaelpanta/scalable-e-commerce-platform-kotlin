import { err, ok, type Result } from './result.ts';

// The policy is identity's (data-model.md §2). The storefront pre-checks only the minimum length
// where a new password is chosen; sign-in has no length pre-check. Never trimmed, never persisted,
// never rendered back: `toString` and `toJSON` are redacted.
export type PasswordError = 'empty' | 'too-short';

export const PASSWORD_MIN_LENGTH = 12;
export const REDACTED = '[redacted]';

export class Password {
  readonly #value: string;

  private constructor(value: string) {
    this.#value = value;
  }

  /** A new password (register, reset completion): length pre-check only. */
  static create(raw: string): Result<Password, PasswordError> {
    if (raw.length === 0) return err('empty');
    if (raw.length < PASSWORD_MIN_LENGTH) return err('too-short');
    return ok(new Password(raw));
  }

  /** An existing password (sign-in, account deletion): no policy pre-check beyond non-empty. */
  static forSignIn(raw: string): Result<Password, PasswordError> {
    if (raw.length === 0) return err('empty');
    return ok(new Password(raw));
  }

  /** The raw value, read exactly once by the API edge when the request body is built. */
  unwrap(): string {
    return this.#value;
  }

  get length(): number {
    return this.#value.length;
  }

  toString(): string {
    return REDACTED;
  }

  toJSON(): string {
    return REDACTED;
  }
}
