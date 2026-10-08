import type { JSX } from 'react';
import { Link } from 'react-router';

import type { CartLineView } from '@app/cart/cartView';
import { productPath } from '@app/catalog/browseParams';
import type { Quantity } from '@domain/quantity';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import styles from './cart.module.css';
import { Money } from './Money.tsx';
import { QuantityInput } from './QuantityInput.tsx';

export type CartLineProps = {
  readonly line: CartLineView;
  readonly busy?: boolean;
  readonly onQuantity: (lineId: string, quantity: Quantity) => void;
  readonly onRemove: (lineId: string) => void;
};

/**
 * One cart line (FR-004): name, current unit price, both prices when the price changed since the
 * line was added, the unavailable warning, the quantity control and the server's line total.
 */
export function CartLine({ line, busy = false, onQuantity, onRemove }: CartLineProps): JSX.Element {
  return (
    <li className={styles.line} aria-label={line.productName}>
      <div className={styles.lineHeader}>
        <h2 className={styles.name}>
          <Link className={styles.nameLink} to={productPath(line)}>
            {line.productName}
          </Link>
        </h2>
        <p className={styles.amount}>
          Unit price <Money value={line.currentPrice} />
        </p>
      </div>
      {line.priceChanged ? (
        <p className={styles.notice}>
          Price changed since you added it: was <Money value={line.priceAtAdd} />, now{' '}
          <Money value={line.currentPrice} />.
        </p>
      ) : null}
      {line.unavailable ? (
        <p className={styles.unavailable}>
          No longer available in this quantity. Remove it to check out.
        </p>
      ) : null}
      <div className={styles.controls}>
        <QuantityInput
          productName={line.productName}
          value={line.quantity}
          busy={busy}
          onCommit={(quantity) => {
            onQuantity(line.id, quantity);
          }}
        />
        <button
          className={cx(buttons.button, buttons.quiet)}
          type="button"
          disabled={busy}
          aria-label={`Remove ${line.productName}`}
          onClick={() => {
            onRemove(line.id);
          }}
        >
          Remove
        </button>
      </div>
      <p className={styles.lineTotal}>
        Line total <Money value={line.lineTotal} />
      </p>
    </li>
  );
}
