import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import type { Listing } from '../catalog/browseParams.ts';
import { usePorts } from '../ports.ts';
import type { OrderPage } from './orderPort.ts';
import { orderKeys } from './useOrder.ts';

/** One page of the signed-in shopper's own orders, newest first (the platform's order). */
export function useOwnOrders(params: Listing): UseQueryResult<OrderPage> {
  const { order: port } = usePorts();
  return useQuery({
    queryKey: orderKeys.list(params.page ?? null, params.size ?? null),
    queryFn: (): Promise<OrderPage> => port.listOwnOrders(params),
    staleTime: 0,
  });
}
