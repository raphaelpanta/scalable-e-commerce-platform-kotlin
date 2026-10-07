import { createContext, useContext } from 'react';

import type { components } from '@api/generated/catalog';

import type { Listing } from './browseParams.ts';

// What the browsing use cases need from the catalogue (contracts/storefront-routes.md, FR-001,
// FR-002); implemented over the generated contract in src/api/catalog.ts and injected through
// `CatalogPortContext` by the composition root. Types are the generated ones, never hand-written.
export type Product = components['schemas']['Product'];
export type ProductPage = components['schemas']['ProductPage'];
export type ProductImage = components['schemas']['ProductImage'];
export type Category = components['schemas']['Category'];
export type CategoryPage = components['schemas']['CategoryPage'];

export type ProductListParams = Listing & {
  readonly q?: string;
  readonly categoryId?: string;
  /** Operator console only: also list withdrawn products (ignored by the platform for others). */
  readonly includeWithdrawn?: boolean;
};

export type StockAdjustmentRequest = components['schemas']['StockAdjustmentRequest'];
export type StockAdjustmentRecord = components['schemas']['StockAdjustment'];

/** One refused field of a request (422 `errors[]`), shown next to the field concerned. */
export type FieldIssue = { readonly field: string; readonly message: string };

export type AdjustStockResult =
  /** 201: the adjustment as recorded, with the quantity before and after. */
  | { readonly kind: 'adjusted'; readonly adjustment: StockAdjustmentRecord }
  /** 422 (or 400): the request is not valid, with the platform's per-field errors. */
  | { readonly kind: 'invalid'; readonly message: string; readonly errors: readonly FieldIssue[] }
  | { readonly kind: 'notFound' }
  /** 403: the caller does not hold the operator role. */
  | { readonly kind: 'forbidden' };

export type CategoryListParams = Listing;

export type CatalogPort = {
  listProducts(params: ProductListParams): Promise<ProductPage>;
  listCategories(params: CategoryListParams): Promise<CategoryPage>;
  /** `null` when the category is unknown or withdrawn (404). */
  getCategory(id: string): Promise<Category | null>;
  /** `null` when the product is unknown or withdrawn (404). */
  getProduct(id: string): Promise<Product | null>;
  /** Operator console: adds or removes units on hand for a reason (`adjustStock`). */
  adjustStock(productId: string, request: StockAdjustmentRequest): Promise<AdjustStockResult>;
};

export const CatalogPortContext = createContext<CatalogPort | undefined>(undefined);

export function useCatalogPort(): CatalogPort {
  const port = useContext(CatalogPortContext);
  if (port === undefined) throw new Error('catalog hooks require a CatalogPortContext provider');
  return port;
}
