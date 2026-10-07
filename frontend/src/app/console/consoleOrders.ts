import { listingFromSearch, parsePage } from '@app/catalog/browseParams';
import type { Order } from '@app/order/orderPort';
import { allowedActions, type OrderAction, OrderStatus, type PaymentStatus } from '@domain/status';

import type { ConsoleOrdersParams } from './consolePort.ts';

// The pure parts of the console's order list and order page (data-model.md §3.3 and §3.4,
// contracts/storefront-routes.md): the list state that lives in the URL, the transitions an
// operator is offered for an order, and the history actor wording.

/** `ConsoleOrderFilter`: the list state of `/console/orders`, all of it in the URL (FR-003). */
export type ConsoleOrderFilter = {
  readonly status?: OrderStatus;
  /** Zero-based page index. */
  readonly page?: number;
  /** 1..100, absent for the platform default (20). */
  readonly size?: number;
};

export const CONSOLE_ORDERS_KEY = 'console-orders';

export const consoleKeys = {
  all: [CONSOLE_ORDERS_KEY] as const,
  list: (params: ConsoleOrdersParams) =>
    [
      CONSOLE_ORDERS_KEY,
      'list',
      { page: params.page ?? null, size: params.size ?? null, status: params.orderStatus ?? null },
    ] as const,
};

/** The filter a console URL stands for; values that do not fit are ignored, never corrected. */
export function filterFromSearch(search: URLSearchParams): ConsoleOrderFilter {
  const raw = search.get('status');
  const page = parsePage(search.get('page'));
  const { size } = listingFromSearch(search);
  return {
    ...(raw !== null && OrderStatus.isOrderStatus(raw) ? { status: raw } : {}),
    ...(page === undefined ? {} : { page }),
    ...(size === undefined ? {} : { size }),
  };
}

export function searchFromFilter(filter: ConsoleOrderFilter): URLSearchParams {
  const search = new URLSearchParams();
  if (filter.status !== undefined) search.set('status', filter.status);
  if (filter.page !== undefined && filter.page > 0) search.set('page', String(filter.page));
  if (filter.size !== undefined) search.set('size', String(filter.size));
  return search;
}

/** The same query with the status replaced (removed for "all") and the first page shown again. */
export function withStatus(
  search: URLSearchParams,
  status: OrderStatus | undefined,
): URLSearchParams {
  const next = new URLSearchParams(search);
  next.delete('page');
  if (status === undefined) next.delete('status');
  else next.set('status', status);
  return next;
}

/** What the order service is asked: the status goes through its `orderStatus` parameter. */
export function listParams(filter: ConsoleOrderFilter): ConsoleOrdersParams {
  return {
    ...(filter.status === undefined ? {} : { orderStatus: filter.status }),
    ...(filter.page === undefined ? {} : { page: filter.page }),
    ...(filter.size === undefined ? {} : { size: filter.size }),
  };
}

type OrderStatuses = { readonly orderStatus: OrderStatus; readonly paymentStatus: PaymentStatus };

/** The transitions an operator is offered: cancel while placed or preparing, the one next step. */
export function consoleActions(order: OrderStatuses): readonly OrderAction[] {
  return allowedActions('operator', order.orderStatus, order.paymentStatus);
}

/** The status the platform is asked to move the order to for an action. */
export function targetOf(action: OrderAction): OrderStatus {
  return action.kind === 'cancel' ? 'cancelled' : action.to;
}

type HistoryEntry = Pick<Order['statusHistory'][number], 'kind' | 'status' | 'by'>;

/**
 * Who a history entry is attributed to, as words: the platform's `by` is an account id (or
 * `system`) and the console never shows an account id. Order moves past `placed` are the
 * operator's; the placement is the shopper's; a cancellation follows its recorded reason.
 */
export function actorOf(
  entry: HistoryEntry,
  order: Pick<Order, 'cancellationReason'>,
): 'Operator' | 'Shopper' | 'System' {
  if (entry.by === 'system' || entry.kind === 'payment') return 'System';
  if (entry.status === 'cancelled') {
    if (order.cancellationReason === 'OPERATOR') return 'Operator';
    return order.cancellationReason === 'SHOPPER_REQUEST' ? 'Shopper' : 'System';
  }
  return entry.status === 'placed' ? 'Shopper' : 'Operator';
}
