import { err, ok, type Result } from './result.ts';

// Integer 1..99, the platform's per-line maximum (data-model.md §2). `Quantity.zero` exists only
// as the "remove line" command and is never a valid `Quantity`.
export type QuantityError = 'not-an-integer' | 'below-minimum' | 'above-maximum';

export type Quantity = { readonly value: number; readonly kind: 'quantity' };
export type RemoveLine = { readonly value: 0; readonly kind: 'remove' };
export type LineQuantityCommand = Quantity | RemoveLine;

export const QUANTITY_MIN = 1;
export const QUANTITY_MAX = 99;

const zero: RemoveLine = Object.freeze({ value: 0, kind: 'remove' } as const);

export const Quantity = {
  parse(input: number): Result<Quantity, QuantityError> {
    if (!Number.isInteger(input)) return err('not-an-integer');
    if (input < QUANTITY_MIN) return err('below-minimum');
    if (input > QUANTITY_MAX) return err('above-maximum');
    return ok({ value: input, kind: 'quantity' });
  },
  /** The remove-line command (`UpdateLineRequest.quantity` 0); not a quantity. */
  zero,
  isRemoval(command: LineQuantityCommand): command is RemoveLine {
    return command.kind === 'remove';
  },
  equals(left: Quantity, right: Quantity): boolean {
    return left.value === right.value;
  },
} as const;
