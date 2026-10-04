import { err, ok, type Result } from './result.ts';

// The only identifiers the storefront mints (data-model.md §1): canonical lower-case UUID v4.
// Platform ids stay plain strings validated for UUID shape at the api edge (`isUuid`).
const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UUID_ANY_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type CorrelationId = { readonly value: string; readonly kind: 'correlation-id' };
export type IdempotencyKey = { readonly value: string; readonly kind: 'idempotency-key' };
export type UuidError = 'not-canonical-uuid-v4';

/** A source of random UUIDs (`crypto.randomUUID` at the edge); injected so this module stays pure. */
export type UuidSource = () => string;

export function isCanonicalUuidV4(candidate: string): boolean {
  return UUID_V4_PATTERN.test(candidate);
}

/** Shape check for platform identifiers in URLs (any RFC 9562 version, either case). */
export function isUuid(candidate: string): boolean {
  return UUID_ANY_PATTERN.test(candidate);
}

export const CorrelationId = {
  parse(input: string): Result<CorrelationId, UuidError> {
    return isCanonicalUuidV4(input)
      ? ok({ value: input, kind: 'correlation-id' })
      : err('not-canonical-uuid-v4');
  },
  generate(source: UuidSource): Result<CorrelationId, UuidError> {
    return CorrelationId.parse(source().toLowerCase());
  },
} as const;

export const IdempotencyKey = {
  parse(input: string): Result<IdempotencyKey, UuidError> {
    return isCanonicalUuidV4(input)
      ? ok({ value: input, kind: 'idempotency-key' })
      : err('not-canonical-uuid-v4');
  },
  generate(source: UuidSource): Result<IdempotencyKey, UuidError> {
    return IdempotencyKey.parse(source().toLowerCase());
  },
} as const;
