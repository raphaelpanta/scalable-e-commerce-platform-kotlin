import type { JSX } from 'react';
import { useSearchParams } from 'react-router';

import { listingFromSearch, normalizeSearchTerm } from '@app/catalog/browseParams';
import { useProducts } from '@app/catalog/useProducts';

import browse from './browse.module.css';
import styles from './pages.module.css';
import { ProductListing } from '../components/ProductListing.tsx';
import { SearchBox } from '../components/SearchBox.tsx';

/**
 * `/search?q=`: products matching the term, ranked as the platform ranks them (FR-001). The term
 * is trimmed and capped at 100 characters; an empty term lists all products with a prompt.
 */
export function SearchPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const term = normalizeSearchTerm(searchParams.get('q'));
  const products = useProducts({
    ...listingFromSearch(searchParams),
    ...(term === '' ? {} : { q: term }),
  });
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Search
      </h1>
      <SearchBox key={term} initialTerm={term} />
      <p className={browse.prompt}>
        {term === ''
          ? 'Type a product name to search. Showing all products.'
          : `Results for “${term}”`}
      </p>
      <ProductListing
        query={products}
        empty={{
          title: term === '' ? 'No products yet' : `Nothing found for “${term}”`,
          message: 'Try another word or browse all products.',
          action: { label: 'Browse all products', to: '/' },
        }}
      />
    </section>
  );
}
