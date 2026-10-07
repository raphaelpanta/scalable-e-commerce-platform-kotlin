import type { components } from '@api/generated/order';
import type { Listing } from '@app/catalog/browseParams';
import type { IdempotencyKey } from '@domain/ids';

// The order operations of checkout and confirmation over the generated order contract. Placing an
// order answers a closed result: every documented refusal of `placeOrder` (409 price-changed,
// 409 insufficient-stock, 409 order-cancelled, 422 payment-declined, 422 idempotency-key-reuse,
// other 4xx) is a value the checkout state machine switches on (data-model.md §3.2); only
// throttling, outages and network failures are thrown.
export type Order = components['schemas']['Order'];
export type OrderLine = components['schemas']['OrderLine'];
export type OrderPage = components['schemas']['OrderPage'];
export type PlaceOrderRequest = components['schemas']['PlaceOrderRequest'];
export type ChangedLine = components['schemas']['PriceChangedProblem']['changedLines'][number];
export type UnavailableLine =
  components['schemas']['InsufficientStockProblem']['unavailableLines'][number];
export type DeclineReason = components['schemas']['DeclineReason'];

export type PlaceOrderResult =
  /** 201 (also an idempotent replay) or 202 (payment pending): the single order. */
  | { readonly kind: 'placed'; readonly order: Order }
  | {
      readonly kind: 'priceChanged';
      readonly changedLines: readonly ChangedLine[];
      readonly currentCartRevision: string;
    }
  | { readonly kind: 'insufficientStock'; readonly unavailableLines: readonly UnavailableLine[] }
  | {
      readonly kind: 'paymentDeclined';
      readonly declineReason: DeclineReason | undefined;
      readonly orderId: string | undefined;
    }
  | {
      readonly kind: 'orderCancelled';
      readonly orderId: string;
      readonly cancellationReason: string;
    }
  | { readonly kind: 'idempotencyConflict' }
  /** Any other refusal (empty cart, unknown address, malformed request): the platform's message. */
  | { readonly kind: 'rejected'; readonly message: string }
  /** The session is gone: the page returns to sign-in with the draft kept. */
  | { readonly kind: 'unauthorized' };

/**
 * What cancelling an own order answers: the cancelled order, the platform's reason for refusing
 * (409 `order-not-cancellable`, the order has moved on) or an unknown order (404). Throttling,
 * outages and the other refusals are thrown.
 */
export type CancelOrderResult =
  | { readonly kind: 'cancelled'; readonly order: Order }
  | { readonly kind: 'notCancellable'; readonly message: string }
  | { readonly kind: 'notFound' };

export type OrderPort = {
  placeOrder(request: PlaceOrderRequest, key: IdempotencyKey): Promise<PlaceOrderResult>;
  /** `null` when the order is unknown or belongs to another shopper (404). */
  getOwnOrder(id: string): Promise<Order | null>;
  /** The own orders, newest first (the platform's order). */
  listOwnOrders(params?: Listing): Promise<OrderPage>;
  cancelOwnOrder(id: string): Promise<CancelOrderResult>;
};
