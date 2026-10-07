import type { Money } from '@domain/money';
import {
  allowedActions,
  type CancellationReason,
  type OrderAction,
  type OrderStatus,
  type PaymentStatus,
  type Role,
} from '@domain/status';

import type { Order, OrderLine } from './orderPort.ts';

// OrderView (data-model.md §3.3), a read-only derivation of the order contract's `Order`: the
// order number shown to the shopper (the order `id`, shortened; the full id stays copyable), the
// payment deadline taken from the order's additive `paymentExpiresAt` (never recomputed in the
// browser, FR-009), which drives the "awaiting payment" countdown and the 5-second polling while
// it is pending, the history with its actors named "you", "operator" or "system" (never an
// account id) and the actions the role may take. Amounts and lines are the platform's.
export const ORDER_NUMBER_LENGTH = 8;
export const ORDER_POLL_INTERVAL_MS = 5_000;

export function orderNumber(id: string): string {
  return id.slice(0, ORDER_NUMBER_LENGTH);
}

type PaymentFields = Pick<Order, 'paymentStatus' | 'paymentExpiresAt'>;

/** The end of the payment window, present only while the payment is pending and the instant parses. */
export function paymentDeadline(order: PaymentFields): Date | undefined {
  if (order.paymentStatus !== 'pending') return undefined;
  if (order.paymentExpiresAt === undefined || order.paymentExpiresAt === null) return undefined;
  const millis = Date.parse(order.paymentExpiresAt);
  return Number.isNaN(millis) ? undefined : new Date(millis);
}

/** True while the payment is pending and its window has not ended. */
export function awaitingPayment(order: PaymentFields, now: Date): boolean {
  const deadline = paymentDeadline(order);
  return deadline !== undefined && now.getTime() < deadline.getTime();
}

/** Polling interval for TanStack Query: every 5 s while awaiting payment, otherwise stopped. */
export function pollInterval(order: PaymentFields | null | undefined, now: Date): number | false {
  if (order === null || order === undefined) return false;
  return awaitingPayment(order, now) ? ORDER_POLL_INTERVAL_MS : false;
}

export type Remaining = { readonly minutes: number; readonly seconds: number };

/** Whole minutes and seconds left until `deadline` (zero once it has passed). */
export function remainingUntil(deadline: Date, now: Date): Remaining {
  const totalSeconds = Math.max(0, Math.ceil((deadline.getTime() - now.getTime()) / 1000));
  return { minutes: Math.floor(totalSeconds / 60), seconds: totalSeconds % 60 };
}

export function formatRemaining({ minutes, seconds }: Remaining): string {
  return `${String(minutes)}:${String(seconds).padStart(2, '0')}`;
}

/** Who made a change, as the shopper sees it: never an account id. */
export type Actor = 'you' | 'operator' | 'system';

export const SYSTEM_ACTOR = 'system';

/**
 * The actor of a history entry: the platform is `system`; the shopper who placed the order (its
 * owner) is `you`; any other account that changed the order is an `operator`.
 */
export function actorOf(by: string, ownerId: string | undefined): Actor {
  if (by === SYSTEM_ACTOR) return 'system';
  return ownerId !== undefined && by === ownerId ? 'you' : 'operator';
}

export type HistoryEntry = {
  readonly kind: 'order' | 'payment';
  readonly status: OrderStatus | PaymentStatus;
  readonly at: Date;
  readonly actor: Actor;
};

export type OrderView = {
  readonly id: string;
  readonly number: string;
  readonly lines: readonly OrderLine[];
  readonly total: Money;
  readonly deliveryAddress: Order['deliveryAddress'];
  readonly orderStatus: OrderStatus;
  readonly paymentStatus: PaymentStatus;
  /** Present only when the order is cancelled. */
  readonly cancellationReason: CancellationReason | undefined;
  /** Present only while the payment is pending. */
  readonly paymentDeadline: Date | undefined;
  /** Oldest first. */
  readonly history: readonly HistoryEntry[];
  readonly createdAt: Date;
  readonly actions: readonly OrderAction[];
};

function instantOf(raw: string): Date {
  return new Date(Date.parse(raw));
}

function historyOf(order: Order): readonly HistoryEntry[] {
  const entries = order.statusHistory
    .map((change, index) => ({ change, index, at: instantOf(change.at) }))
    .sort((left, right) => left.at.getTime() - right.at.getTime() || left.index - right.index);
  const placed = entries.find(
    ({ change }) => change.kind === 'order' && change.status === 'placed',
  );
  const ownerId = placed?.change.by;
  return entries.map(({ change, at }) => ({
    kind: change.kind,
    status: change.status,
    at,
    actor: actorOf(change.by, ownerId),
  }));
}

/** The order as the shopper sees it (role `shopper`) or, with `role`, as that role's actions go. */
export function toOrderView(order: Order, role: Role = 'shopper'): OrderView {
  const cancelled = order.orderStatus === 'cancelled';
  return {
    id: order.id,
    number: orderNumber(order.id),
    lines: order.lines,
    total: order.total,
    deliveryAddress: order.deliveryAddress,
    orderStatus: order.orderStatus,
    paymentStatus: order.paymentStatus,
    cancellationReason: cancelled ? (order.cancellationReason ?? undefined) : undefined,
    paymentDeadline: paymentDeadline(order),
    history: historyOf(order),
    createdAt: instantOf(order.createdAt),
    actions: allowedActions(role, order.orderStatus, order.paymentStatus),
  };
}
