import type { CatalogPort, CategoryListParams, ProductListParams } from '@app/catalog/catalogPort';

import { type ApiClientOptions, createApiClient, hasStatus, requireBody } from './client.ts';
import type { paths as CatalogPaths } from './generated/catalog';

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

export function createCatalogApi(options: ApiClientOptions = {}): CatalogPort {
  const client = createApiClient<CatalogPaths>(options);
  return {
    async listProducts(params) {
      const { data, response } = await client.GET('/api/v1/catalog/products', {
        params: { query: productQuery(params) },
      });
      return requireBody(data, response);
    },
    async listCategories(params) {
      const { data, response } = await client.GET('/api/v1/catalog/categories', {
        params: { query: listingQuery(params) },
      });
      return requireBody(data, response);
    },
    async getCategory(id) {
      try {
        const { data, response } = await client.GET('/api/v1/catalog/categories/{categoryId}', {
          params: { path: { categoryId: id } },
        });
        return requireBody(data, response);
      } catch (error) {
        if (hasStatus(error, 404)) return null;
        throw error;
      }
    },
    async getProduct(id) {
      try {
        const { data, response } = await client.GET('/api/v1/catalog/products/{productId}', {
          params: { path: { productId: id } },
        });
        return requireBody(data, response);
      } catch (error) {
        if (hasStatus(error, 404)) return null;
        throw error;
      }
    },
  };
}
