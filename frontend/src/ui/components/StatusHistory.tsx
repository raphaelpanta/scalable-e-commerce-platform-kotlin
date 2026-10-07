import type { JSX } from 'react';

import type { HistoryEntry } from '@app/order/orderView';

import { dateTimeText } from '../format.ts';
import { orderStatusLabel, paymentStatusLabel } from '../labels.ts';
import styles from './orders.module.css';

export type StatusHistoryProps = {
  readonly entries: readonly HistoryEntry[];
};

function statusText(entry: HistoryEntry): string {
  return entry.kind === 'order'
    ? `Order: ${orderStatusLabel(entry.status)}`
    : `Payment: ${paymentStatusLabel(entry.status)}`;
}

/**
 * Every status change with its time and who made it: "you", "operator" or "system", never an
 * account id (data-model.md §3.3). Oldest first, as the view orders them.
 */
export function StatusHistory({ entries }: StatusHistoryProps): JSX.Element {
  return (
    <ol className={styles.history} aria-label="Status history">
      {entries.map((entry, index) => (
        <li key={`${String(index)}-${entry.kind}-${entry.status}`} className={styles.historyEntry}>
          <span>{statusText(entry)}</span>
          <span className={styles.actor}>by {entry.actor}</span>
          <time dateTime={entry.at.toISOString()}>{dateTimeText(entry.at)}</time>
        </li>
      ))}
    </ol>
  );
}
