import type { JSX } from 'react';
import { Link, useParams } from 'react-router';

import { actorOf } from '@app/console/consoleOrders';
import type { Order } from '@app/order/orderPort';
import { orderNumber } from '@app/order/orderView';
import { useOwnOrder, usePaymentAttempts } from '@app/order/useOrder';
import { isUuid } from '@domain/ids';

import styles from './console.module.css';
import { formatInstant } from './format.ts';
import { platformRefusal } from './refusal.ts';
import { TransitionButtons } from './TransitionButtons.tsx';
import buttons from '../components/buttons.module.css';
import cart from '../components/cart.module.css';
import { OrderSummary } from '../components/OrderSummary.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import {
  cancellationLabel,
  declineLabel,
  orderStatusLabel,
  paymentStatusLabel,
} from '../labels.ts';
import { NotAllowedPage } from '../pages/NotAllowedPage.tsx';
import { NotFoundPage } from '../pages/NotFoundPage.tsx';
import pages from '../pages/pages.module.css';

const OUTCOME_LABELS: Readonly<Record<string, string>> = {
  approved: 'Approved',
  declined: 'Declined',
  pending: 'Pending',
  voided: 'Voided',
};

function History({ order }: { readonly order: Order }): JSX.Element {
  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <caption>Status history</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Change</th>
            <th scope="col">By</th>
          </tr>
        </thead>
        <tbody>
          {order.statusHistory.map((entry, index) => (
            <tr key={`${entry.kind}-${entry.status}-${String(index)}`}>
              <td>{formatInstant(entry.at)}</td>
              <td>
                {entry.kind === 'order' ? 'Order' : 'Payment'}:{' '}
                {entry.kind === 'order'
                  ? orderStatusLabel(entry.status)
                  : paymentStatusLabel(entry.status)}
              </td>
              <td>{actorOf(entry, order)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PaymentAttempts({ orderId }: { readonly orderId: string }): JSX.Element {
  const attempts = usePaymentAttempts(orderId, true);
  if (attempts.isError) return <p>The payment attempts could not be loaded.</p>;
  if (attempts.data === undefined) return <p role="status">Loading payment attempts…</p>;
  if (attempts.data.items.length === 0) return <p>No payment attempts yet.</p>;
  return (
    <div className={styles.scroller}>
      <table className={styles.table}>
        <caption>Payment attempts</caption>
        <thead>
          <tr>
            <th scope="col">When</th>
            <th scope="col">Outcome</th>
            <th scope="col">Detail</th>
          </tr>
        </thead>
        <tbody>
          {attempts.data.items.map((attempt) => (
            <tr key={attempt.id}>
              <td>{formatInstant(attempt.createdAt)}</td>
              <td>{OUTCOME_LABELS[attempt.outcome] ?? attempt.outcome}</td>
              <td>{attempt.outcome === 'declined' ? declineLabel(attempt.declineReason) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function OrderDetails({ order }: { readonly order: Order }): JSX.Element {
  const cancelled = order.orderStatus === 'cancelled';
  return (
    <section className={pages.page} aria-labelledby="page-title">
      <h1 id="page-title" className={pages.title}>
        Order {orderNumber(order.id)}
      </h1>
      <p>
        Full order id: <code className={styles.code}>{order.id}</code>
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
        <dt>Ordered</dt>
        <dd>{formatInstant(order.createdAt)}</dd>
      </dl>
      <TransitionButtons order={order} />
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
        Delivery to {order.deliveryAddress.recipientName}, {order.deliveryAddress.line1}
        {order.deliveryAddress.line2 === undefined || order.deliveryAddress.line2 === null
          ? ''
          : `, ${order.deliveryAddress.line2}`}
        , {order.deliveryAddress.postalCode} {order.deliveryAddress.city},{' '}
        {order.deliveryAddress.country}.
      </p>
      <History order={order} />
      <PaymentAttempts orderId={order.id} />
      <p className={pages.actions}>
        <Link className={buttons.link} to="/console/orders">
          Back to the orders
        </Link>
      </p>
    </section>
  );
}

/**
 * `/console/orders/:id`: one order with lines, amounts, address and status history, the payment
 * attempts, and only the transitions the platform allows (FR-011). Polled like the shopper's
 * page while its payment is pending; a 403 renders the "not allowed" state and no data.
 */
export function ConsoleOrderPage(): JSX.Element {
  const { id } = useParams();
  const orderId = id !== undefined && isUuid(id) ? id.toLowerCase() : undefined;
  const order = useOwnOrder(orderId);
  const refusal = platformRefusal(order.error);
  if (orderId === undefined) return <NotFoundPage />;
  if (refusal !== undefined) {
    return <NotAllowedPage {...(refusal.detail === undefined ? {} : { detail: refusal.detail })} />;
  }
  return (
    <QueryBoundary query={order} loadingLabel="Loading the order…">
      {(found) => (found === null ? <NotFoundPage /> : <OrderDetails order={found} />)}
    </QueryBoundary>
  );
}
