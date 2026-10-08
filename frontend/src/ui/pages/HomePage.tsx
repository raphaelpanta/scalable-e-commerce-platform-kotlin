import type { JSX } from 'react';
import { Link, useSearchParams } from 'react-router';

import { categoryPath, listingFromSearch } from '@app/catalog/browseParams';
import { selectFeaturedCategories } from '@app/catalog/featured';
import { useCategories } from '@app/catalog/useCategories';
import { useProducts } from '@app/catalog/useProducts';

import browse from './browse.module.css';
import home from './home.module.css';
import styles from './pages.module.css';
import { BRAND_TAGLINE } from '../brand/brand.ts';
import { CategoryNav } from '../components/CategoryNav.tsx';
import { ProductListing } from '../components/ProductListing.tsx';

/** `/`: the active products, paged by `?page=` and `?size=`, with the categories to browse. */
export function HomePage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const products = useProducts(listingFromSearch(searchParams));
  const categories = useCategories();
  const featured = selectFeaturedCategories(categories.data?.items ?? []);
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Products
      </h1>
      <p className={home.lead}>{BRAND_TAGLINE}</p>
      {featured.length === 0 ? null : (
        <ul className={home.tiles} aria-label="Featured categories">
          {featured.map((category) => (
            <li key={category.id} className={home.tile}>
              <Link className={home.tileLink} to={categoryPath(category)}>
                {category.name}
              </Link>
            </li>
          ))}
        </ul>
      )}
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
