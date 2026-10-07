import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { type CategoryListParams, type CategoryPage, useCatalogPort } from './catalogPort.ts';
import { categoriesQuery } from './catalogQueries.ts';

/** The largest page the catalogue allows (`size` maximum 100): the navigation lists every category it can. */
const CATEGORY_NAV_SIZE = 100;

/** The categories offered for browsing (navigation of every browsing page). */
export function useCategories(
  params: CategoryListParams = { size: CATEGORY_NAV_SIZE },
): UseQueryResult<CategoryPage> {
  const port = useCatalogPort();
  return useQuery(categoriesQuery(port, params));
}
