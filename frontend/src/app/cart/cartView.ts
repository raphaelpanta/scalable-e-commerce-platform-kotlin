import { CartRevision } from '@domain/cartRevision';
import type { Money } from '@domain/money';

import type { CappedLine, Cart, CartLine } from './cartPort.ts';

// CartView of data-model.md §3.2: a read-only derivation of the cart contract's `Cart`. Every
// amount is the server's (no total is ever computed here); `unavailable` is derived from what the
// storefront learnt elsewhere (a catalogue lookup or a refused checkout) and blocks checkout
// until the line is removed; `mergeNotice` is shown once after sign-in.
export type CartLineView = {
  readonly id: string;
  readonly productId: string;
  readonly productName: string;
  /** The quantity as the platform reports it (1..99). */
  readonly quantity: number;
  readonly priceAtAdd: Money;
  readonly currentPrice: Money;
  readonly lineTotal: Money;
  readonly priceChanged: boolean;
  readonly unavailable: boolean;
};

export type MergeNotice = readonly CappedLine[];

export type CartView = {
  readonly id: string | undefined;
  readonly revision: CartRevision | undefined;
  readonly lines: readonly CartLineView[];
  /** The server's total; absent when the platform knows no cart. */
  readonly total: Money | undefined;
  readonly updatedAt: Date | undefined;
  /** Units across all lines (the header badge). */
  readonly itemCount: number;
  readonly isEmpty: boolean;
  /** False while a line is unavailable (edge case "stale cart") or the cart is empty. */
  readonly canCheckout: boolean;
  readonly mergeNotice: MergeNotice | undefined;
};

export type CartContext = {
  /** Products the catalogue reported withdrawn or out of stock, or a refused checkout named. */
  readonly unavailableProductIds?: ReadonlySet<string>;
  readonly mergeNotice?: MergeNotice | undefined;
};

export const EMPTY_CART_VIEW: CartView = Object.freeze({
  id: undefined,
  revision: undefined,
  lines: [],
  total: undefined,
  updatedAt: undefined,
  itemCount: 0,
  isEmpty: true,
  canCheckout: false,
  mergeNotice: undefined,
});

function parseInstant(raw: string): Date | undefined {
  const millis = Date.parse(raw);
  return Number.isNaN(millis) ? undefined : new Date(millis);
}

function lineView(line: CartLine, unavailable: ReadonlySet<string>): CartLineView {
  return {
    id: line.id,
    productId: line.productId,
    productName: line.productName,
    quantity: line.quantity,
    priceAtAdd: line.priceAtAdd,
    currentPrice: line.currentPrice,
    lineTotal: line.lineTotal,
    priceChanged: line.priceChanged,
    unavailable: unavailable.has(line.productId),
  };
}

/** Units in the cart: the sum of the line quantities. */
export function itemCount(lines: ReadonlyArray<Pick<CartLine, 'quantity'>>): number {
  return lines.reduce((sum, line) => sum + line.quantity, 0);
}

export function toCartView(cart: Cart | null, context: CartContext = {}): CartView {
  const notice =
    context.mergeNotice === undefined || context.mergeNotice.length === 0
      ? undefined
      : context.mergeNotice;
  if (cart === null) return { ...EMPTY_CART_VIEW, mergeNotice: notice };
  const unavailable = context.unavailableProductIds ?? new Set<string>();
  const lines = cart.lines.map((line) => lineView(line, unavailable));
  const revision = CartRevision.parse(cart.revision);
  return {
    id: cart.id,
    revision: revision.ok ? revision.value : undefined,
    lines,
    total: cart.total,
    updatedAt: parseInstant(cart.updatedAt),
    itemCount: itemCount(lines),
    isEmpty: lines.length === 0,
    canCheckout: lines.length > 0 && lines.every((line) => !line.unavailable),
    mergeNotice: notice,
  };
}
