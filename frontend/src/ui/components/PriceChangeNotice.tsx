import type { JSX } from 'react';

import type { ChangedLine } from '@app/order/orderPort';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import { Money } from './Money.tsx';
import styles from './notices.module.css';

export type PriceChangeNoticeProps = {
  readonly changedLines: readonly ChangedLine[];
  /** Product names by product id (from the cart), so the lines read as the shopper knows them. */
  readonly names: ReadonlyMap<string, string>;
  readonly busy?: boolean;
  readonly onAccept: () => void;
};

/**
 * The 409 `price-changed` refusal (FR-007): old and new price per line and the explicit
 * acceptance the platform requires before the order can be resubmitted at the new prices.
 */
export function PriceChangeNotice({
  changedLines,
  names,
  busy = false,
  onAccept,
}: PriceChangeNoticeProps): JSX.Element {
  return (
    <div className={cx(styles.notice, styles.warning)} role="alert">
      <h2 className={styles.title}>Prices changed</h2>
      <p className={styles.message}>
        The price of one or more items changed since you last viewed the cart. Review the new prices
        and accept them to place the order.
      </p>
      <ul className={styles.changes}>
        {changedLines.map((line) => (
          <li key={line.lineId}>
            {names.get(line.productId) ?? 'Item'}: was <Money value={line.oldPrice} />, now{' '}
            <Money value={line.newPrice} />
          </li>
        ))}
      </ul>
      <button className={buttons.button} type="button" disabled={busy} onClick={onAccept}>
        Accept the new prices
      </button>
    </div>
  );
}
