import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import {
  type CatalogPort,
  type ProductListParams,
  type ProductPage,
  useCatalogPort,
} from './catalogPort.ts';
import { productsQuery } from './catalogQueries.ts';

export type UseProductsOptions = {
  /** False while the page cannot ask yet (for example an unresolved category id). */
  readonly enabled?: boolean;
};

/** A page of products for the home, category and search routes; the key holds every parameter. */
export function useProducts(
  params: ProductListParams,
  { enabled = true }: UseProductsOptions = {},
): UseQueryResult<ProductPage> {
  const port: CatalogPort = useCatalogPort();
  return useQuery({ ...productsQuery(port, params), enabled });
}
