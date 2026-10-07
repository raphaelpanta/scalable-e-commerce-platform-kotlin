import { type QueryClient, queryOptions, type UseQueryOptions } from '@tanstack/react-query';

import { type LineQuantityCommand, Quantity } from '@domain/quantity';

import type { Cart, CartPort } from './cartPort.ts';
import type { MergeNotice } from './cartView.ts';

// The cart lives in the TanStack Query cache under `CART_KEY`; every write replaces it with the
// server's answer (the cart contract returns the whole recalculated cart). A quantity change is
// applied optimistically to the quantity alone (totals stay the server's) and rolled back when
// the platform refuses it (422). The merge notice and the products a refused checkout named are
// kept beside it so the cart view can derive `mergeNotice` and `unavailable` (data-model.md §3.2).
export const CART_KEY = ['cart'] as const;
export const MERGE_NOTICE_KEY = ['cart', 'mergeNotice'] as const;
export const UNAVAILABLE_KEY = ['cart', 'unavailable'] as const;

export function cartQuery(
  port: CartPort,
): UseQueryOptions<Cart | null, Error, Cart | null, typeof CART_KEY> {
  return queryOptions({
    queryKey: CART_KEY,
    queryFn: (): Promise<Cart | null> => port.getCart(),
    // Prices and stock move under the cart; every visit re-reads it.
    staleTime: 0,
  });
}

/**
 * The cart as it will look once the platform accepts `command` on `lineId`: the quantity replaced,
 * a removal dropping the line, every amount untouched (the server recalculates them). An unknown
 * line changes nothing.
 */
export function optimisticQuantity(cart: Cart, lineId: string, command: LineQuantityCommand): Cart {
  if (!cart.lines.some((line) => line.id === lineId)) return cart;
  const lines = Quantity.isRemoval(command)
    ? cart.lines.filter((line) => line.id !== lineId)
    : cart.lines.map((line) => (line.id === lineId ? { ...line, quantity: command.value } : line));
  return { ...cart, lines };
}

export type CartActions = {
  add(productId: string, quantity: Quantity): Promise<Cart>;
  /** Optimistic; rolled back to the previous cart when the platform refuses. */
  setQuantity(lineId: string, command: LineQuantityCommand): Promise<Cart>;
  remove(lineId: string): Promise<Cart>;
  clear(): Promise<void>;
  /** Merges after sign-in: the account cart replaces the cached one; capped lines become the notice. */
  merge(): Promise<MergeNotice | null>;
  dismissMergeNotice(): void;
  /** Products a refused checkout named: their lines are shown unavailable until removed. */
  flagUnavailable(productIds: readonly string[]): void;
};

export function createCartActions(queryClient: QueryClient, port: CartPort): CartActions {
  const replace = (cart: Cart | null): void => {
    queryClient.setQueryData<Cart | null>(CART_KEY, cart);
    // A cart the platform just returned is current: a line it still holds is not known
    // unavailable until a checkout says so again.
    queryClient.setQueryData<readonly string[]>(UNAVAILABLE_KEY, []);
  };
  return {
    async add(productId, quantity) {
      const cart = await port.addLine(productId, quantity);
      replace(cart);
      return cart;
    },
    async setQuantity(lineId, command) {
      const previous = queryClient.getQueryData<Cart | null>(CART_KEY);
      if (previous !== undefined && previous !== null) {
        queryClient.setQueryData<Cart | null>(
          CART_KEY,
          optimisticQuantity(previous, lineId, command),
        );
      }
      try {
        const cart = await port.setLineQuantity(lineId, command);
        replace(cart);
        return cart;
      } catch (error) {
        queryClient.setQueryData<Cart | null>(CART_KEY, previous ?? null);
        throw error;
      }
    },
    async remove(lineId) {
      const cart = await port.removeLine(lineId);
      replace(cart);
      return cart;
    },
    async clear() {
      await port.clearCart();
      await queryClient.invalidateQueries({ queryKey: CART_KEY });
    },
    async merge() {
      const result = await port.mergeCart();
      if (result === null) {
        // Nothing was merged: the account cart is whatever the platform holds now.
        await queryClient.invalidateQueries({ queryKey: CART_KEY });
        return null;
      }
      replace(result.cart);
      queryClient.setQueryData<MergeNotice>(MERGE_NOTICE_KEY, result.cappedLines);
      return result.cappedLines;
    },
    dismissMergeNotice() {
      queryClient.setQueryData<MergeNotice>(MERGE_NOTICE_KEY, []);
    },
    flagUnavailable(productIds) {
      queryClient.setQueryData<readonly string[]>(UNAVAILABLE_KEY, [...new Set(productIds)]);
    },
  };
}
