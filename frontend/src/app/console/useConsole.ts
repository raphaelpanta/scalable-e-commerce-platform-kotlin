import {
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';

import {
  type AdjustStockResult,
  type StockAdjustmentRequest,
  useCatalogPort,
} from '@app/catalog/catalogPort';
import { catalogKeys } from '@app/catalog/catalogQueries';
import { orderKeys } from '@app/order/useOrder';
import type { OrderStatus } from '@domain/status';

import { type ConsoleOrderFilter, consoleKeys, listParams } from './consoleOrders.ts';
import { type OrderPage, type TransitionResult, useOrderConsolePort } from './consolePort.ts';

/** One page of every shopper's orders for the console list, read again whenever the page opens. */
export function useConsoleOrders(filter: ConsoleOrderFilter): UseQueryResult<OrderPage> {
  const port = useOrderConsolePort();
  const params = listParams(filter);
  return useQuery({
    queryKey: consoleKeys.list(params),
    queryFn: (): Promise<OrderPage> => port.listOrders(params),
    staleTime: 0,
  });
}

/**
 * Moves one order to a status (advance or the operator's cancellation). A refusal comes back as a
 * value and leaves the displayed order untouched; a success replaces the cached order, so every
 * view of it (and of the console list) shows the new status.
 */
export function useTransition(
  orderId: string,
): UseMutationResult<TransitionResult, Error, OrderStatus> {
  const port = useOrderConsolePort();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (target: OrderStatus): Promise<TransitionResult> =>
      port.transitionOrderStatus(orderId, target),
    onSuccess: async (result) => {
      if (result.kind !== 'transitioned') return;
      queryClient.setQueryData(orderKeys.order(orderId), result.order);
      await queryClient.invalidateQueries({ queryKey: consoleKeys.all });
    },
  });
}

export type StockAdjustmentCommand = {
  readonly productId: string;
  readonly request: StockAdjustmentRequest;
};

/** Adjusts the stock of one product; a success refreshes every catalogue view (shoppers' too). */
export function useAdjustStock(): UseMutationResult<
  AdjustStockResult,
  Error,
  StockAdjustmentCommand
> {
  const port = useCatalogPort();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ productId, request }: StockAdjustmentCommand): Promise<AdjustStockResult> =>
      port.adjustStock(productId, request),
    onSuccess: async (result) => {
      if (result.kind === 'adjusted') {
        await queryClient.invalidateQueries({ queryKey: catalogKeys.all });
      }
    },
  });
}
