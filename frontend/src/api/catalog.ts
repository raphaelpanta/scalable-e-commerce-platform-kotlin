import type { CatalogPort, CategoryListParams, ProductListParams } from '@app/catalog/catalogPort';

import { type ApiClientOptions, createApiClient, hasStatus, requireBody } from './client.ts';
import type { paths as CatalogPaths } from './generated/catalog';
import { ProblemError } from './problem.ts';

// Adapter of the catalogue port over the generated catalog contract (`listProducts`,
// `listCategories`, `getCategory`, `getProduct` and the console's `adjustStock`). Only the
// parameters the storefront sets are sent; a 404 on a detail lookup is a legitimate answer
// (unknown or withdrawn) and becomes `null`; the refusals of `adjustStock` (404, 403, 422) are
// values the console renders.
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
    ...(params.includeWithdrawn === true ? { includeWithdrawn: true } : {}),
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
    async adjustStock(productId, request) {
      try {
        const { data, response } = await client.POST(
          '/api/v1/catalog/products/{productId}/stock-adjustments',
          { params: { path: { productId } }, body: request },
        );
        return { kind: 'adjusted', adjustment: requireBody(data, response) };
      } catch (error) {
        if (hasStatus(error, 404)) return { kind: 'notFound' };
        if (hasStatus(error, 403)) return { kind: 'forbidden' };
        if (error instanceof ProblemError && hasStatus(error, 400, 422)) {
          const { problem } = error;
          return {
            kind: 'invalid',
            message: problem.detail ?? problem.title,
            errors: problem.errors,
          };
        }
        throw error;
      }
    },
  };
}
