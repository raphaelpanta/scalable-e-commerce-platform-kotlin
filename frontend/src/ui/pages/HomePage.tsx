import type { JSX } from 'react';
import { useSearchParams } from 'react-router';

import { listingFromSearch } from '@app/catalog/browseParams';
import { useProducts } from '@app/catalog/useProducts';

import browse from './browse.module.css';
import styles from './pages.module.css';
import { CategoryNav } from '../components/CategoryNav.tsx';
import { ProductListing } from '../components/ProductListing.tsx';

/** `/`: the active products, paged by `?page=` and `?size=`, with the categories to browse. */
export function HomePage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const products = useProducts(listingFromSearch(searchParams));
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Products
      </h1>
      <div className={browse.layout}>
        <CategoryNav />
        <div className={browse.results}>
          <ProductListing
            query={products}
            empty={{
              title: 'No products yet',
              message: 'The catalogue is empty for now.',
              action: { label: 'Search the store', to: '/search' },
            }}
          />
        </div>
      </div>
    </section>
  );
}
