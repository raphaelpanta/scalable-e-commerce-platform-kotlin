import { err, ok, type Result } from './result.ts';

// Opaque, non-empty; compared for equality only, never parsed or ordered (data-model.md §2).
export type CartRevision = { readonly value: string };

export const CartRevision = {
  parse(input: string): Result<CartRevision, 'empty'> {
    return input.length === 0 ? err('empty') : ok({ value: input });
  },
  equals(left: CartRevision, right: CartRevision): boolean {
    return left.value === right.value;
  },
} as const;
