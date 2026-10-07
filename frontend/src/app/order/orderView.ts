import type { Order } from './orderPort.ts';

// The parts of OrderView (data-model.md §3.3) the confirmation page needs: the order number shown
// to the shopper (the order `id`, shortened; the full id stays copyable), and the payment deadline
// taken from the order's additive `paymentExpiresAt` (never recomputed in the browser, FR-009),
// which drives the "awaiting payment" countdown and the 5-second polling while it is pending.
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
