import {
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import type { PaymentAttempt, PaymentAttemptPage } from '../payment/paymentPort.ts';
import { usePorts } from '../ports.ts';
import type { CancelOrderResult, Order } from './orderPort.ts';
import { pollInterval } from './orderView.ts';

export const ORDERS_KEY = 'orders';

export const orderKeys = {
  order: (id: string) => [ORDERS_KEY, 'own', id] as const,
  attempts: (id: string) => [ORDERS_KEY, 'attempts', id] as const,
  attempt: (id: string) => [ORDERS_KEY, 'attempt', id] as const,
  list: (page: number | null, size: number | null) => [ORDERS_KEY, 'list', page, size] as const,
  lists: () => [ORDERS_KEY, 'list'] as const,
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

/** One payment attempt by id (the order's latest one), read only when the page needs it. */
export function usePaymentAttempt(
  attemptId: string | null | undefined,
  enabled: boolean,
): UseQueryResult<PaymentAttempt | null> {
  const { payment: port } = usePorts();
  return useQuery({
    queryKey: orderKeys.attempt(attemptId ?? ''),
    queryFn: (): Promise<PaymentAttempt | null> => port.getAttempt(attemptId ?? ''),
    enabled: enabled && typeof attemptId === 'string',
  });
}

/**
 * Cancels an own order. A cancelled order replaces the cached one and the lists are refreshed;
 * a refusal (`notCancellable`) leaves every cached status as it was (FR-010).
 */
export function useCancelOrder(): UseMutationResult<CancelOrderResult, Error, string> {
  const { order: port } = usePorts();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string): Promise<CancelOrderResult> => port.cancelOwnOrder(id),
    onSuccess: async (result, id) => {
      if (result.kind !== 'cancelled') return;
      queryClient.setQueryData(orderKeys.order(id), result.order);
      await queryClient.invalidateQueries({ queryKey: orderKeys.lists() });
    },
  });
}
