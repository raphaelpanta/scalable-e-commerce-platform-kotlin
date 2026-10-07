import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import type { PaymentAttemptPage } from '../payment/paymentPort.ts';
import { usePorts } from '../ports.ts';
import type { Order } from './orderPort.ts';
import { pollInterval } from './orderView.ts';

export const ORDERS_KEY = 'orders';

export const orderKeys = {
  order: (id: string) => [ORDERS_KEY, 'own', id] as const,
  attempts: (id: string) => [ORDERS_KEY, 'attempts', id] as const,
};

/**
 * One own order (`undefined` id: no request). Polled every 5 s while its payment is pending and
 * the payment window has not ended; stopped on a final status or past the deadline (FR-009).
 */
export function useOwnOrder(id: string | undefined): UseQueryResult<Order | null> {
  const { order: port } = usePorts();
  return useQuery({
    queryKey: orderKeys.order(id ?? ''),
    queryFn: (): Promise<Order | null> => port.getOwnOrder(id ?? ''),
    enabled: id !== undefined,
    staleTime: 0,
    refetchInterval: (query) => pollInterval(query.state.data, new Date()),
  });
}

/** The payment attempts of an order, read only when the page needs them. */
export function usePaymentAttempts(
  orderId: string | undefined,
  enabled: boolean,
): UseQueryResult<PaymentAttemptPage> {
  const { payment: port } = usePorts();
  return useQuery({
    queryKey: orderKeys.attempts(orderId ?? ''),
    queryFn: (): Promise<PaymentAttemptPage> => port.listAttempts(orderId ?? ''),
    enabled: enabled && orderId !== undefined,
  });
}
