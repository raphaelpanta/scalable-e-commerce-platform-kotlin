import type { DeclineReason } from '@app/order/orderPort';
import type { CancellationReason, OrderStatus, PaymentStatus } from '@domain/status';

// Shopper-facing wording of the platform's enumerations (FR-009, FR-016): never a raw wire value.
export const ORDER_STATUS_LABELS: Readonly<Record<OrderStatus, string>> = {
  placed: 'Placed',
  preparing: 'Being prepared',
  shipped: 'Shipped',
  delivered: 'Delivered',
  cancelled: 'Cancelled',
};

export const PAYMENT_STATUS_LABELS: Readonly<Record<PaymentStatus, string>> = {
  pending: 'Awaiting payment',
  approved: 'Approved',
  failed: 'Failed',
};

export const CANCELLATION_LABELS: Readonly<Record<CancellationReason, string>> = {
  SHOPPER_REQUEST: 'cancelled at your request',
  OPERATOR: 'cancelled by the store',
  PAYMENT_FAILED: 'cancelled because the payment failed',
  PAYMENT_EXPIRED: 'cancelled because the payment window ended',
};

export const DECLINE_LABELS: Readonly<Record<DeclineReason, string>> = {
  insufficient_funds: 'insufficient funds',
  card_expired: 'the card has expired',
  card_rejected: 'the card was rejected',
  suspected_fraud: 'the payment was flagged as suspicious',
  invalid_payment_method: 'the payment method is not valid',
};

export function orderStatusLabel(status: string): string {
  return (ORDER_STATUS_LABELS as Readonly<Record<string, string>>)[status] ?? status;
}

export function paymentStatusLabel(status: string): string {
  return (PAYMENT_STATUS_LABELS as Readonly<Record<string, string>>)[status] ?? status;
}

export function cancellationLabel(reason: string): string {
  return (CANCELLATION_LABELS as Readonly<Record<string, string>>)[reason] ?? 'cancelled';
}

export function declineLabel(reason: string | undefined): string {
  if (reason === undefined) return 'the payment provider declined the charge';
  return (DECLINE_LABELS as Readonly<Record<string, string>>)[reason] ?? 'the charge was declined';
}
