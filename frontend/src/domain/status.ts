// Order and payment status transitions copied from feature 004 (data-model.md §2.1). These pure
// functions only decide which actions to offer; the platform still validates every transition.
export const ORDER_STATUSES = ['placed', 'preparing', 'shipped', 'delivered', 'cancelled'] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

export const PAYMENT_STATUSES = ['pending', 'approved', 'failed'] as const;
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

export const ROLES = ['shopper', 'operator'] as const;
export type Role = (typeof ROLES)[number];

export const CANCELLATION_REASONS = [
  'SHOPPER_REQUEST',
  'OPERATOR',
  'PAYMENT_FAILED',
  'PAYMENT_EXPIRED',
] as const;
export type CancellationReason = (typeof CANCELLATION_REASONS)[number];

export type OrderAction =
  | { readonly kind: 'cancel' }
  | { readonly kind: 'advance'; readonly to: Exclude<OrderStatus, 'placed' | 'cancelled'> };

const ORDER_NEXT: Readonly<Record<OrderStatus, readonly OrderStatus[]>> = {
  placed: ['preparing', 'cancelled'],
  preparing: ['shipped', 'cancelled'],
  shipped: ['delivered'],
  delivered: [],
  cancelled: [],
};

const PAYMENT_NEXT: Readonly<Record<PaymentStatus, readonly PaymentStatus[]>> = {
  pending: ['approved', 'failed'],
  approved: [],
  failed: [],
};

export const OrderStatus = {
  allowedNext(status: OrderStatus): readonly OrderStatus[] {
    return ORDER_NEXT[status];
  },
  isTerminal(status: OrderStatus): boolean {
    return ORDER_NEXT[status].length === 0;
  },
  isOrderStatus(candidate: string): candidate is OrderStatus {
    return (ORDER_STATUSES as readonly string[]).includes(candidate);
  },
} as const;

export const PaymentStatus = {
  allowedNext(status: PaymentStatus): readonly PaymentStatus[] {
    return PAYMENT_NEXT[status];
  },
  isTerminal(status: PaymentStatus): boolean {
    return PAYMENT_NEXT[status].length === 0;
  },
  isPaymentStatus(candidate: string): candidate is PaymentStatus {
    return (PAYMENT_STATUSES as readonly string[]).includes(candidate);
  },
} as const;

/** Shoppers cancel only while `placed`; operators while `placed` or `preparing`. */
export function cancelAllowed(role: Role, status: OrderStatus): boolean {
  if (status === 'placed') return true;
  return role === 'operator' && status === 'preparing';
}

type AdvanceTarget = Extract<OrderAction, { kind: 'advance' }>['to'];

/** The one forward step an operator may take from each status (none from a terminal one). */
const ADVANCE_TARGET: Readonly<Record<OrderStatus, AdvanceTarget | undefined>> = {
  placed: 'preparing',
  preparing: 'shipped',
  shipped: 'delivered',
  delivered: undefined,
  cancelled: undefined,
};

/** `advance(preparing)` additionally requires payment `approved`. */
export function advanceTarget(
  orderStatus: OrderStatus,
  paymentStatus: PaymentStatus,
): AdvanceTarget | undefined {
  const target = ADVANCE_TARGET[orderStatus];
  if (target === 'preparing' && paymentStatus !== 'approved') return undefined;
  return target;
}

/**
 * Actions to offer for (role, order status, payment status), data-model.md §3.3. `advance` is for
 * operators only; `advance(preparing)` additionally requires payment `approved`. Empty for
 * terminal statuses (no advance target, and `cancelAllowed` is false there).
 */
export function allowedActions(
  role: Role,
  orderStatus: OrderStatus,
  paymentStatus: PaymentStatus,
): readonly OrderAction[] {
  const actions: OrderAction[] = [];
  if (role === 'operator') {
    const to = advanceTarget(orderStatus, paymentStatus);
    if (to !== undefined) actions.push({ kind: 'advance', to });
  }
  if (cancelAllowed(role, orderStatus)) actions.push({ kind: 'cancel' });
  return actions;
}
