import type { JSX } from 'react';
import { Link } from 'react-router';

import { productPath } from '@app/catalog/browseParams';
import type { Product } from '@app/catalog/catalogPort';

import { cx } from '../cx.ts';
import { Money } from './Money.tsx';
import styles from './ProductCard.module.css';
import { ProductImage } from './ProductImage.tsx';

export type ProductCardProps = {
  readonly product: Product;
};

/** Primary image first, then any other, as the platform lists them. */
export function orderedImages(product: Product): Product['images'] {
  return [...product.images].sort((left, right) => Number(right.primary) - Number(left.primary));
}

/** One product of a listing: primary image, name (the link), price and availability (FR-001). */
export function ProductCard({ product }: ProductCardProps): JSX.Element {
  const primary = orderedImages(product)[0];
  const inStock = product.availability.inStock;
  return (
    <li className={styles.card}>
      <ProductImage src={primary?.url} alt={primary?.altText ?? product.name} />
      <h2 className={styles.name}>
        <Link className={styles.link} to={productPath(product)}>
          {product.name}
        </Link>
      </h2>
      <p className={styles.price}>
        <Money value={product.price} />
      </p>
      <p className={cx(styles.availability, !inStock && styles.outOfStock)}>
        {inStock ? 'In stock' : 'Out of stock'}
      </p>
    </li>
  );
}
