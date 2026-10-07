import type { JSX } from 'react';
import { Link } from 'react-router';

import type { OrderView } from '@app/order/orderView';

import { dateTimeText } from '../format.ts';
import { Money } from './Money.tsx';
import styles from './orders.module.css';
import { StatusBadges } from './StatusBadges.tsx';

export type OrderRowProps = {
  readonly order: OrderView;
};

/** One order of the history: number (a link to the order), date, both statuses and the total. */
export function OrderRow({ order }: OrderRowProps): JSX.Element {
  return (
    <li className={styles.row}>
      <div>
        <Link className={styles.rowTitle} to={`/orders/${order.id}`}>
          Order {order.number}
        </Link>
        <p className={styles.rowMeta}>
          <time dateTime={order.createdAt.toISOString()}>{dateTimeText(order.createdAt)}</time>
        </p>
      </div>
      <div>
        <StatusBadges orderStatus={order.orderStatus} paymentStatus={order.paymentStatus} />
        <p className={styles.rowTotal}>
          <Money value={order.total} />
        </p>
      </div>
    </li>
  );
}
