import type { components } from '@api/generated/cart';
import type { LineQuantityCommand, Quantity } from '@domain/quantity';

// What the cart use cases need (contracts/storefront-routes.md `/cart`, `/products/:id`, FR-004):
// the cart contract's operations as the gateway forwards them in cookie mode. The storefront never
// sees or stores the cart token: the gateway keeps it in the `cart` cookie. Types are generated.
export type Cart = components['schemas']['Cart'];
export type CartLine = components['schemas']['CartLine'];
export type MergeResult = components['schemas']['MergeResult'];
export type CappedLine = components['schemas']['CappedLine'];

export type CartPort = {
  /** The current cart; `null` when the platform knows no cart (an unknown or consumed token, 404). */
  getCart(): Promise<Cart | null>;
  /** Adds `quantity` of a product (201); the refusals (404 unknown product, 422 stock) are thrown. */
  addLine(productId: string, quantity: Quantity): Promise<Cart>;
  /** Sets a line's quantity; `Quantity.zero` removes the line (`UpdateLineRequest.quantity` 0). */
  setLineQuantity(lineId: string, command: LineQuantityCommand): Promise<Cart>;
  removeLine(lineId: string): Promise<Cart>;
  clearCart(): Promise<void>;
  /**
   * Merges the anonymous cart into the account cart after sign-in (the gateway supplies both
   * credentials); `null` when there was nothing to merge (no anonymous cart, or already merged).
   */
  mergeCart(): Promise<MergeResult | null>;
};
