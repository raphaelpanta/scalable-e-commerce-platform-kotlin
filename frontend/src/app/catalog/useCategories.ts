import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { type CategoryListParams, type CategoryPage, useCatalogPort } from './catalogPort.ts';
import { categoriesQuery } from './catalogQueries.ts';

/** The categories offered for browsing (navigation of every browsing page). */
export function useCategories(params: CategoryListParams = {}): UseQueryResult<CategoryPage> {
  const port = useCatalogPort();
  return useQuery(categoriesQuery(port, params));
}
