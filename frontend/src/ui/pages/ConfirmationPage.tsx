import { type JSX, useState } from 'react';
import { Link, useParams } from 'react-router';

import type { Order } from '@app/order/orderPort';
import { orderNumber, paymentDeadline } from '@app/order/orderView';
import { useOwnOrder, usePaymentAttempts } from '@app/order/useOrder';
import { isUuid } from '@domain/ids';

import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import cart from '../components/cart.module.css';
import { OrderSummary } from '../components/OrderSummary.tsx';
import { PaymentCountdown } from '../components/PaymentCountdown.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { cx } from '../cx.ts';
import {
  cancellationLabel,
  declineLabel,
  orderStatusLabel,
  paymentStatusLabel,
} from '../labels.ts';

function PaymentOutcome({ order }: { readonly order: Order }): JSX.Element | null {
  const failed = order.paymentStatus === 'failed';
  const attempts = usePaymentAttempts(order.id, failed);
  const deadline = paymentDeadline(order);
  if (deadline !== undefined) return <PaymentCountdown deadline={deadline} />;
  if (!failed) return null;
  const latest = attempts.data?.items.find((attempt) => attempt.outcome === 'declined');
  return (
    <p role="alert">
      The payment failed: {declineLabel(latest?.declineReason)}. Nothing was charged.
    </p>
  );
}

function Confirmation({ order }: { readonly order: Order }): JSX.Element {
  const [copied, setCopied] = useState(false);
  const pending = order.paymentStatus === 'pending';
  const cancelled = order.orderStatus === 'cancelled';
  const copy = async (): Promise<void> => {
    await navigator.clipboard.writeText(order.id);
    setCopied(true);
  };
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        {cancelled ? 'Order cancelled' : pending ? 'Order received' : 'Order confirmed'}
      </h1>
      <p>
        Order number <strong>{orderNumber(order.id)}</strong>
      </p>
      <p className={styles.actions}>
        <span>
          Full order id: <code className={cart.code}>{order.id}</code>
        </span>
        <button
          className={cx(buttons.button, buttons.secondary)}
          type="button"
          onClick={() => {
            void copy();
          }}
        >
          Copy order id
        </button>
        {copied ? <span role="status">Order id copied.</span> : null}
      </p>
      <dl className={cart.statuses}>
        <dt>Order status</dt>
        <dd>
          {orderStatusLabel(order.orderStatus)}
          {cancelled && order.cancellationReason !== undefined && order.cancellationReason !== null
            ? ` (${cancellationLabel(order.cancellationReason)})`
            : ''}
        </dd>
        <dt>Payment status</dt>
        <dd>{paymentStatusLabel(order.paymentStatus)}</dd>
      </dl>
      <PaymentOutcome order={order} />
      <OrderSummary
        caption="Items"
        lines={order.lines.map((line) => ({
          key: line.productId,
          name: line.name,
          quantity: line.quantity,
          unitPrice: line.unitPrice,
          lineTotal: line.lineTotal,
        }))}
        total={order.total}
      />
      <p>
        Delivery to {order.deliveryAddress.recipientName}, {order.deliveryAddress.line1},{' '}
        {order.deliveryAddress.postalCode} {order.deliveryAddress.city},{' '}
        {order.deliveryAddress.country}.
      </p>
      {order.paymentStatus === 'approved' ? <p>Your cart is now empty.</p> : null}
      <p className={styles.actions}>
        <Link className={buttons.button} to="/">
          Continue shopping
        </Link>
        <Link className={buttons.link} to="/orders">
          Your orders
        </Link>
      </p>
    </section>
  );
}

/**
 * `/orders/:id/confirmation` (FR-009): the order number (the id, shortened, with the full id
 * copyable), lines, amounts, both statuses, the "awaiting payment" countdown from the order's
 * `paymentExpiresAt` with the 5-second refresh while pending, and the failed-payment explanation.
 */
export function ConfirmationPage(): JSX.Element {
  const { id } = useParams();
  const orderId = id !== undefined && isUuid(id) ? id.toLowerCase() : undefined;
  const order = useOwnOrder(orderId);
  if (orderId === undefined) return <NotFoundPage />;
  return (
    <QueryBoundary query={order} loadingLabel="Loading your order…">
      {(found) => (found === null ? <NotFoundPage /> : <Confirmation order={found} />)}
    </QueryBoundary>
  );
}
