import type { JSX } from 'react';

import type { Money as MoneyValue } from '@domain/money';

import styles from './cart.module.css';
import { Money } from './Money.tsx';

export type SummaryLine = {
  readonly key: string;
  readonly name: string;
  readonly quantity: number;
  readonly unitPrice: MoneyValue;
  readonly lineTotal: MoneyValue;
};

export type OrderSummaryProps = {
  readonly caption: string;
  readonly lines: readonly SummaryLine[];
  /** The server's total; never computed here. */
  readonly total: MoneyValue;
};

/** Lines and amounts as the platform reports them (checkout review, confirmation). */
export function OrderSummary({ caption, lines, total }: OrderSummaryProps): JSX.Element {
  return (
    <div className={styles.summary}>
      <table className={styles.table}>
        <caption className={styles.label}>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Item</th>
            <th scope="col" className={styles.numeric}>
              Quantity
            </th>
            <th scope="col" className={styles.numeric}>
              Unit price
            </th>
            <th scope="col">Line total</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr key={line.key}>
              <th scope="row">{line.name}</th>
              <td className={styles.numeric}>{line.quantity}</td>
              <td className={styles.numeric}>
                <Money value={line.unitPrice} />
              </td>
              <td>
                <Money value={line.lineTotal} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className={styles.total}>
        <span>Total</span>
        <Money value={total} />
      </p>
    </div>
  );
}
