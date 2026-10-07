import type { JSX } from 'react';

import type { OrderStatus, PaymentStatus } from '@domain/status';

import { orderStatusLabel, paymentStatusLabel } from '../labels.ts';
import styles from './orders.module.css';

export type StatusBadgesProps = {
  readonly orderStatus: OrderStatus;
  readonly paymentStatus: PaymentStatus;
};

/** Both statuses of an order, always together (FR-009), in the shopper's words. */
export function StatusBadges({ orderStatus, paymentStatus }: StatusBadgesProps): JSX.Element {
  return (
    <span className={styles.badges}>
      <span className={styles.badge}>Order: {orderStatusLabel(orderStatus)}</span>
      <span className={styles.badge}>Payment: {paymentStatusLabel(paymentStatus)}</span>
    </span>
  );
}
