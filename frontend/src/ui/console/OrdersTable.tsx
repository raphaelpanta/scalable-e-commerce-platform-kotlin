import type { JSX } from 'react';
import { Link } from 'react-router';

import type { Order } from '@app/order/orderPort';
import { orderNumber } from '@app/order/orderView';

import styles from './console.module.css';
import { formatInstant } from './format.ts';
import { Money } from '../components/Money.tsx';
import { orderStatusLabel, paymentStatusLabel } from '../labels.ts';

export type OrdersTableProps = {
  readonly orders: readonly Order[];
};

/**
 * Every order of the page with both statuses, newest first as the platform lists them; each row
 * links to the order page. The shopper is not named here: the list shows what the order carries.
 */
export function OrdersTable({ orders }: OrdersTableProps): JSX.Element {
  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <caption>Orders</caption>
        <thead>
          <tr>
            <th scope="col">Order</th>
            <th scope="col">Ordered</th>
            <th scope="col" className={styles.numeric}>
              Total
            </th>
            <th scope="col">Order status</th>
            <th scope="col">Payment status</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.id}>
              <th scope="row">
                <Link to={`/console/orders/${order.id}`}>{orderNumber(order.id)}</Link>
              </th>
              <td>{formatInstant(order.createdAt)}</td>
              <td className={styles.numeric}>
                <Money value={order.total} />
              </td>
              <td>{orderStatusLabel(order.orderStatus)}</td>
              <td>{paymentStatusLabel(order.paymentStatus)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
