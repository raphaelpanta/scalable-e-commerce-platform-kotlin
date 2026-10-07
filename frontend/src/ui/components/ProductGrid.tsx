import type { JSX } from 'react';

import type { Product } from '@app/catalog/catalogPort';

import { ProductCard } from './ProductCard.tsx';
import styles from './ProductGrid.module.css';

export type ProductGridProps = {
  readonly products: readonly Product[];
};

/** The products of one page, in the order the platform returned them (FR-001). */
export function ProductGrid({ products }: ProductGridProps): JSX.Element {
  return (
    <ul className={styles.grid} aria-label="Products">
      {products.map((product) => (
        <ProductCard key={product.id} product={product} />
      ))}
    </ul>
  );
}
