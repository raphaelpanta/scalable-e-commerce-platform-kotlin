import { queryOptions, type UseQueryOptions } from '@tanstack/react-query';

import type {
  CatalogPort,
  Category,
  CategoryListParams,
  CategoryPage,
  Product,
  ProductListParams,
  ProductPage,
} from './catalogPort.ts';

// Query keys carry every URL parameter of the request (page, size, q, categoryId), so two views
// that differ in any of them never share a cache entry (FR-003). Absent parameters are `null`
// rather than omitted to keep the keys explicit and stable.
export const CATALOG_KEY = 'catalog';

export const catalogKeys = {
  all: [CATALOG_KEY] as const,
  products: (params: ProductListParams) =>
    [
      CATALOG_KEY,
      'products',
      {
        page: params.page ?? null,
        size: params.size ?? null,
        q: params.q ?? null,
        categoryId: params.categoryId ?? null,
      },
    ] as const,
  categories: (params: CategoryListParams) =>
    [CATALOG_KEY, 'categories', { page: params.page ?? null, size: params.size ?? null }] as const,
  category: (id: string) => [CATALOG_KEY, 'category', id] as const,
  product: (id: string) => [CATALOG_KEY, 'product', id] as const,
};

type Options<TData, TKey extends readonly unknown[]> = UseQueryOptions<TData, Error, TData, TKey>;

export function productsQuery(
  port: CatalogPort,
  params: ProductListParams,
): Options<ProductPage, ReturnType<typeof catalogKeys.products>> {
  return queryOptions({
    queryKey: catalogKeys.products(params),
    queryFn: () => port.listProducts(params),
  });
}

export function categoriesQuery(
  port: CatalogPort,
  params: CategoryListParams = {},
): Options<CategoryPage, ReturnType<typeof catalogKeys.categories>> {
  return queryOptions({
    queryKey: catalogKeys.categories(params),
    queryFn: () => port.listCategories(params),
    // Categories change rarely; one fetch serves the navigation of every page for a while.
    staleTime: 5 * 60_000,
  });
}

export function categoryQuery(
  port: CatalogPort,
  id: string,
): Options<Category | null, ReturnType<typeof catalogKeys.category>> {
  return queryOptions({
    queryKey: catalogKeys.category(id),
    queryFn: (): Promise<Category | null> => port.getCategory(id),
  });
}

export function productQuery(
  port: CatalogPort,
  id: string,
): Options<Product | null, ReturnType<typeof catalogKeys.product>> {
  return queryOptions({
    queryKey: catalogKeys.product(id),
    queryFn: (): Promise<Product | null> => port.getProduct(id),
  });
}
