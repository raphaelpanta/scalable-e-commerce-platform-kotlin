import type { UseQueryResult } from '@tanstack/react-query';
import type { JSX, ReactNode } from 'react';
import { useSearchParams } from 'react-router';

import { withPage } from '@app/catalog/browseParams';
import type { ProductPage } from '@app/catalog/catalogPort';

import { Empty, type EmptyProps } from './Empty.tsx';
import { Pager } from './Pager.tsx';
import { ProductGrid } from './ProductGrid.tsx';
import { QueryBoundary } from './QueryBoundary.tsx';

export type ProductListingProps = {
  readonly query: UseQueryResult<ProductPage>;
  /** The empty state of this listing: what it means here and the one next action. */
  readonly empty: EmptyProps;
};

/** A page of products with every state of FR-016: loading, empty, error, throttled, results. */
export function ProductListing({ query, empty }: ProductListingProps): JSX.Element {
  const [searchParams] = useSearchParams();
  return (
    <QueryBoundary query={query} loadingLabel="Loading products…" loading="grid">
      {(page): ReactNode => {
        if (page.items.length === 0) {
          if (page.page > 0) {
            const first = withPage(searchParams, 0).toString();
            return (
              <Empty
                title="No more products"
                message="This page is past the end of the list."
                action={{ label: 'Back to the first page', to: first === '' ? '.' : `?${first}` }}
              />
            );
          }
          return <Empty {...empty} />;
        }
        return (
          <>
            <ProductGrid products={page.items} />
            <Pager
              page={page.page}
              size={page.size}
              totalItems={page.totalItems}
              shown={page.items.length}
            />
          </>
        );
      }}
    </QueryBoundary>
  );
}
