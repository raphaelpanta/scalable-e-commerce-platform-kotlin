import { useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { useMemo } from 'react';

import { usePorts } from '../ports.ts';
import type { Cart } from './cartPort.ts';
import {
  type CartActions,
  cartQuery,
  createCartActions,
  MERGE_NOTICE_KEY,
  UNAVAILABLE_KEY,
} from './cartStore.ts';
import { type CartView, type MergeNotice, toCartView } from './cartView.ts';

export type CartHandle = {
  readonly view: CartView;
  readonly query: UseQueryResult<Cart | null>;
  readonly actions: CartActions;
};

/** The cart of the current visitor (anonymous or signed in) with the actions that change it. */
export function useCart(): CartHandle {
  const { cart: port } = usePorts();
  const queryClient = useQueryClient();
  const query = useQuery(cartQuery(port));
  const mergeNotice = useQuery({
    queryKey: MERGE_NOTICE_KEY,
    queryFn: (): MergeNotice => [],
    staleTime: Infinity,
    gcTime: Infinity,
    enabled: false,
  });
  const unavailable = useQuery({
    queryKey: UNAVAILABLE_KEY,
    queryFn: (): readonly string[] => [],
    staleTime: Infinity,
    gcTime: Infinity,
    enabled: false,
  });
  const actions = useMemo(() => createCartActions(queryClient, port), [queryClient, port]);
  const unavailableIds = unavailable.data;
  const view = useMemo(
    () =>
      toCartView(query.data ?? null, {
        unavailableProductIds: new Set(unavailableIds ?? []),
        mergeNotice: mergeNotice.data,
      }),
    [query.data, unavailableIds, mergeNotice.data],
  );
  return { view, query, actions };
}
