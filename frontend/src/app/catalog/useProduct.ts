import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { type Product, useCatalogPort } from './catalogPort.ts';
import { productQuery } from './catalogQueries.ts';

/**
 * One product by id. `undefined` means the path carried no valid UUID: no request is made and the
 * page renders not-found; `null` data means the platform answered 404 (unknown or withdrawn).
 */
export function useProduct(id: string | undefined): UseQueryResult<Product | null> {
  const port = useCatalogPort();
  return useQuery({ ...productQuery(port, id ?? ''), enabled: id !== undefined });
}
