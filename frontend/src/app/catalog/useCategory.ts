import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { type Category, useCatalogPort } from './catalogPort.ts';
import { categoryQuery } from './catalogQueries.ts';

/**
 * One category by id (the category page title). `undefined` means the path carried no valid id:
 * no request is made and the page renders not-found (storefront-routes.md).
 */
export function useCategory(id: string | undefined): UseQueryResult<Category | null> {
  const port = useCatalogPort();
  return useQuery({ ...categoryQuery(port, id ?? ''), enabled: id !== undefined });
}
