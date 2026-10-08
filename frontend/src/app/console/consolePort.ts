import { createContext, useContext } from 'react';

import type { components as OrderComponents } from '@api/generated/order';
import type { Listing } from '@app/catalog/browseParams';
import type { Order } from '@app/order/orderPort';
import type { OrderStatus } from '@domain/status';

// What the operator console needs beyond the shopper ports (contracts/storefront-routes.md,
// `/console/*`): the operator's listing of every order with the `orderStatus` filter, the
// lifecycle transition (also the operator's cancellation). The
// refusals the console shows are values, never thrown: a 409 is explained and changes nothing, a
// 403 is the platform refusing a caller without the operator role. Outages, throttling and 401
// stay thrown for the shared error states and the session layer.
export type OrderPage = OrderComponents['schemas']['OrderPage'];

export type ConsoleOrdersParams = Listing & { readonly orderStatus?: OrderStatus };

export type TransitionResult =
  /** 200: the order in its new status. */
  | { readonly kind: 'transitioned'; readonly order: Order }
  /** 409 (`invalid-transition`) or a malformed request: the platform's explanation, nothing changed. */
  | { readonly kind: 'refused'; readonly message: string }
  | { readonly kind: 'notFound' }
  /** 403: the caller does not hold the operator role. */
  | { readonly kind: 'forbidden' };

export type OrderConsolePort = {
  /** Every shopper's orders, newest first; `orderStatus` narrows the page (operator only). */
  listOrders(params: ConsoleOrdersParams): Promise<OrderPage>;
  /** Moves an order along the lifecycle; `cancelled` is the operator's cancellation. */
  transitionOrderStatus(orderId: string, target: OrderStatus): Promise<TransitionResult>;
};

export const OrderConsolePortContext = createContext<OrderConsolePort | undefined>(undefined);

export function useOrderConsolePort(): OrderConsolePort {
  const port = useContext(OrderConsolePortContext);
  if (port === undefined) {
    throw new Error('console hooks require an OrderConsolePortContext provider');
  }
  return port;
}
