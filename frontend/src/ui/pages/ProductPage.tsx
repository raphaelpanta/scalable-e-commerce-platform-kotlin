import { type JSX, useId } from 'react';
import { Link, useParams } from 'react-router';

import { productIdFromParam } from '@app/catalog/browseParams';
import type { Product } from '@app/catalog/catalogPort';
import { useProduct } from '@app/catalog/useProduct';
import { correlation } from '@app/correlation';

import browse from './browse.module.css';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import { Money } from '../components/Money.tsx';
import { orderedImages } from '../components/ProductCard.tsx';
import { ProductImage } from '../components/ProductImage.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { cx } from '../cx.ts';

export type ProductPageProps = {
  /** The cart use case (US2); until it lands the control is wired to nothing. */
  readonly onAddToCart?: (productId: string) => void;
};

const noop = (): void => undefined;

type ProductDetailsProps = {
  readonly product: Product;
  readonly onAddToCart: (productId: string) => void;
};

function ProductDetails({ product, onAddToCart }: ProductDetailsProps): JSX.Element {
  const explanationId = useId();
  const inStock = product.availability.inStock;
  const [primary, ...others] = orderedImages(product);
  const description = product.description ?? '';
  return (
    <article className={cx(styles.page, browse.product)} aria-labelledby="page-title">
      <div className={browse.gallery}>
        <ProductImage src={primary?.url} alt={primary?.altText ?? product.name} />
        {others.length === 0 ? null : (
          <ul className={browse.thumbnails} aria-label="More images">
            {others.map((image) => (
              <li key={image.id}>
                <ProductImage src={image.url} alt={image.altText ?? product.name} />
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className={browse.details}>
        <h1 id="page-title" className={styles.title}>
          {product.name}
        </h1>
        <p className={browse.price}>
          <Money value={product.price} />
        </p>
        <p className={cx(browse.availability, !inStock && browse.outOfStock)}>
          {inStock ? 'In stock' : 'Out of stock'}
        </p>
        {description === '' ? null : <p className={browse.description}>{description}</p>}
        <div className={browse.actions}>
          <button
            className={buttons.button}
            type="button"
            disabled={!inStock}
            aria-describedby={inStock ? undefined : explanationId}
            onClick={() => {
              correlation.next();
              onAddToCart(product.id);
            }}
          >
            Add to cart
          </button>
          {inStock ? null : (
            <p id={explanationId} className={browse.explanation}>
              This product is out of stock and cannot be added to the cart.
            </p>
          )}
        </div>
        <p>
          <Link className={buttons.link} to="/">
            Back to products
          </Link>
        </p>
      </div>
    </article>
  );
}

/**
 * `/products/:id`: description, images, price and availability (FR-002). The id is validated
 * before any request; unknown or withdrawn renders the not-found page; out of stock disables
 * "Add to cart" with an explanation.
 */
export function ProductPage({ onAddToCart = noop }: ProductPageProps): JSX.Element {
  const { id } = useParams();
  const productId = productIdFromParam(id);
  const product = useProduct(productId);
  if (productId === undefined) return <NotFoundPage />;
  return (
    <QueryBoundary query={product} loadingLabel="Loading product…">
      {(found) =>
        found === null ? (
          <NotFoundPage />
        ) : (
          <ProductDetails product={found} onAddToCart={onAddToCart} />
        )
      }
    </QueryBoundary>
  );
}
