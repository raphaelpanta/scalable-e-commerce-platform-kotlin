import type { JSX } from 'react';
import { useParams, useSearchParams } from 'react-router';

import { catalogIdFromParam, listingFromSearch } from '@app/catalog/browseParams';
import { useCategory } from '@app/catalog/useCategory';
import { useProducts } from '@app/catalog/useProducts';

import browse from './browse.module.css';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './pages.module.css';
import { CategoryNav } from '../components/CategoryNav.tsx';
import { ProductListing } from '../components/ProductListing.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';

/**
 * `/categories/:slugOrId`: the category's active products (and its descendants'), paged. The id
 * is validated before any request; a slug prefix is ignored; unknown or withdrawn is not-found.
 */
export function CategoryPage(): JSX.Element {
  const { id } = useParams();
  const categoryId = catalogIdFromParam(id);
  const [searchParams] = useSearchParams();
  const category = useCategory(categoryId);
  const products = useProducts(
    { ...listingFromSearch(searchParams), ...(categoryId === undefined ? {} : { categoryId }) },
    { enabled: categoryId !== undefined },
  );
  if (categoryId === undefined) return <NotFoundPage />;
  return (
    <QueryBoundary query={category} loadingLabel="Loading category…">
      {(found) =>
        found === null ? (
          <NotFoundPage />
        ) : (
          <section className={styles.page} aria-labelledby="page-title">
            <h1 id="page-title" className={styles.title}>
              {found.name}
            </h1>
            {found.description === undefined || found.description === '' ? null : (
              <p className={browse.lead}>{found.description}</p>
            )}
            <div className={browse.layout}>
              <CategoryNav currentId={categoryId} />
              <div className={browse.results}>
                <ProductListing
                  query={products}
                  empty={{
                    title: 'No products in this category',
                    message: 'Nothing is on sale here at the moment.',
                    action: { label: 'Browse all products', to: '/' },
                  }}
                />
              </div>
            </div>
          </section>
        )
      }
    </QueryBoundary>
  );
}
