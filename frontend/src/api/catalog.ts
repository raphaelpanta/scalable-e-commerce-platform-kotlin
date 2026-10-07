import type { CatalogPort, CategoryListParams, ProductListParams } from '@app/catalog/catalogPort';

import { type ApiClientOptions, createApiClient } from './client.ts';
import type { paths as CatalogPaths } from './generated/catalog';
import { ApiError, UnavailableError } from './problem.ts';

// Adapter of the catalogue port over the generated catalog contract (`listProducts`,
// `listCategories`, `getCategory`, `getProduct`). Only the parameters the storefront sets are sent;
// a 404 on a detail lookup is a legitimate answer (unknown or withdrawn) and becomes `null`.
type ProductQuery = NonNullable<
  CatalogPaths['/api/v1/catalog/products']['get']['parameters']['query']
>;
type CategoryQuery = NonNullable<
  CatalogPaths['/api/v1/catalog/categories']['get']['parameters']['query']
>;

function listingQuery(params: CategoryListParams): CategoryQuery {
  return {
    ...(params.page === undefined ? {} : { page: params.page }),
    ...(params.size === undefined ? {} : { size: params.size }),
  };
}

function productQuery(params: ProductListParams): ProductQuery {
  return {
    ...listingQuery(params),
    ...(params.q === undefined || params.q === '' ? {} : { q: params.q }),
    ...(params.categoryId === undefined ? {} : { categoryId: params.categoryId }),
  };
}

function required<T>(data: T | undefined, response: Response): T {
  if (data === undefined) {
    throw new UnavailableError(response.headers.get('X-Correlation-Id') ?? undefined, 'empty body');
  }
  return data;
}

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.problem.status === 404;
}

export function createCatalogApi(options: ApiClientOptions = {}): CatalogPort {
  const client = createApiClient<CatalogPaths>(options);
  return {
    async listProducts(params) {
      const { data, response } = await client.GET('/api/v1/catalog/products', {
        params: { query: productQuery(params) },
      });
      return required(data, response);
    },
    async listCategories(params) {
      const { data, response } = await client.GET('/api/v1/catalog/categories', {
        params: { query: listingQuery(params) },
      });
      return required(data, response);
    },
    async getCategory(id) {
      try {
        const { data, response } = await client.GET('/api/v1/catalog/categories/{categoryId}', {
          params: { path: { categoryId: id } },
        });
        return required(data, response);
      } catch (error) {
        if (isNotFound(error)) return null;
        throw error;
      }
    },
    async getProduct(id) {
      try {
        const { data, response } = await client.GET('/api/v1/catalog/products/{productId}', {
          params: { path: { productId: id } },
        });
        return required(data, response);
      } catch (error) {
        if (isNotFound(error)) return null;
        throw error;
      }
    },
  };
}
