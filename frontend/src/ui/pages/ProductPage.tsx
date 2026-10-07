import { type JSX, type SubmitEvent, useId, useState } from 'react';
import { Link, useParams } from 'react-router';

import { useCart } from '@app/cart/useCart';
import { productIdFromParam } from '@app/catalog/browseParams';
import type { Product } from '@app/catalog/catalogPort';
import { useProduct } from '@app/catalog/useProduct';
import { correlation } from '@app/correlation';
import { Quantity, QUANTITY_MAX, QUANTITY_MIN } from '@domain/quantity';

import browse from './browse.module.css';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import cart from '../components/cart.module.css';
import { describeError } from '../components/ErrorState.tsx';
import { Money } from '../components/Money.tsx';
import { orderedImages } from '../components/ProductCard.tsx';
import { ProductImage } from '../components/ProductImage.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import states from '../components/states.module.css';
import { cx } from '../cx.ts';

type AddState =
  | { readonly kind: 'idle' }
  | { readonly kind: 'adding' }
  | { readonly kind: 'added'; readonly quantity: number }
  | { readonly kind: 'failed'; readonly message: string };

type ProductDetailsProps = {
  readonly product: Product;
};

function ProductDetails({ product }: ProductDetailsProps): JSX.Element {
  const explanationId = useId();
  const quantityId = useId();
  const { actions } = useCart();
  const [quantityText, setQuantityText] = useState('1');
  const [adding, setAdding] = useState<AddState>({ kind: 'idle' });
  const inStock = product.availability.inStock;
  const [primary, ...others] = orderedImages(product);
  const description = product.description ?? '';

  const addToCart = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const quantity = Quantity.parse(quantityText.trim() === '' ? Number.NaN : Number(quantityText));
    if (!quantity.ok) {
      setAdding({
        kind: 'failed',
        message: `Enter a whole number between ${QUANTITY_MIN} and ${QUANTITY_MAX}.`,
      });
      return;
    }
    correlation.next();
    setAdding({ kind: 'adding' });
    try {
      await actions.add(product.id, quantity.value);
      setAdding({ kind: 'added', quantity: quantity.value.value });
    } catch (error: unknown) {
      setAdding({
        kind: 'failed',
        message: describeError(error).message ?? 'The product could not be added.',
      });
    }
  };
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
        <form className={browse.actions} onSubmit={(event) => void addToCart(event)} noValidate>
          <div className={cart.quantityField}>
            <label htmlFor={quantityId}>Quantity</label>
            <input
              id={quantityId}
              className={cart.quantityInput}
              type="number"
              inputMode="numeric"
              min={QUANTITY_MIN}
              max={QUANTITY_MAX}
              step={1}
              value={quantityText}
              disabled={!inStock || adding.kind === 'adding'}
              onChange={(event) => {
                setQuantityText(event.target.value);
              }}
            />
          </div>
          <button
            className={buttons.button}
            type="submit"
            disabled={!inStock || adding.kind === 'adding'}
            aria-describedby={inStock ? undefined : explanationId}
          >
            Add to cart
          </button>
          {inStock ? null : (
            <p id={explanationId} className={browse.explanation}>
              This product is out of stock and cannot be added to the cart.
            </p>
          )}
          {adding.kind === 'added' ? (
            <p role="status">
              Added {adding.quantity} to your cart.{' '}
              <Link className={buttons.link} to="/cart">
                View cart
              </Link>
            </p>
          ) : null}
          {adding.kind === 'failed' ? (
            <p role="alert" className={cx(states.state, states.danger)}>
              {adding.message}
            </p>
          ) : null}
        </form>
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
 * `/products/:id`: description, images, price and availability (FR-002), and the way into the cart
 * (FR-004: a quantity and "Add to cart"; the platform's refusal is shown as it explains it). The
 * id is validated before any request; unknown or withdrawn renders the not-found page; out of
 * stock disables "Add to cart" with an explanation.
 */
export function ProductPage(): JSX.Element {
  const { id } = useParams();
  const productId = productIdFromParam(id);
  const product = useProduct(productId);
  if (productId === undefined) return <NotFoundPage />;
  return (
    <QueryBoundary query={product} loadingLabel="Loading product…">
      {(found) => (found === null ? <NotFoundPage /> : <ProductDetails product={found} />)}
    </QueryBoundary>
  );
}
