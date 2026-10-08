import type { Category } from './catalogPort.ts';

/** The editorial home page features at most this many categories. */
export const MAX_FEATURED_CATEGORIES = 4;

/**
 * The categories featured on the home page: the first few active top-level categories, in the
 * order the catalogue lists them (research §10). Pure; the input is not modified.
 */
export function selectFeaturedCategories(categories: readonly Category[]): readonly Category[] {
  return categories
    .filter((category) => category.status === 'active' && (category.parentId ?? null) === null)
    .slice(0, MAX_FEATURED_CATEGORIES);
}
