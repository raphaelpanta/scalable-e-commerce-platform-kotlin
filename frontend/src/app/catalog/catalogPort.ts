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
};

export type CategoryListParams = Listing;

export type CatalogPort = {
  listProducts(params: ProductListParams): Promise<ProductPage>;
  listCategories(params: CategoryListParams): Promise<CategoryPage>;
  /** `null` when the category is unknown or withdrawn (404). */
  getCategory(id: string): Promise<Category | null>;
  /** `null` when the product is unknown or withdrawn (404). */
  getProduct(id: string): Promise<Product | null>;
};

export const CatalogPortContext = createContext<CatalogPort | undefined>(undefined);

export function useCatalogPort(): CatalogPort {
  const port = useContext(CatalogPortContext);
  if (port === undefined) throw new Error('catalog hooks require a CatalogPortContext provider');
  return port;
}
