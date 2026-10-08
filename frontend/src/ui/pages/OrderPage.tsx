import { type JSX, useState } from 'react';
import { Link, useParams } from 'react-router';

import { correlation } from '@app/correlation';
import type { Order } from '@app/order/orderPort';
import { type OrderView, toOrderView } from '@app/order/orderView';
import { useCancelOrder, useOwnOrder, usePaymentAttempt } from '@app/order/useOrder';
import { isUuid } from '@domain/ids';

import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './pages.module.css';
import { ActionError } from '../components/ActionError.tsx';
import buttons from '../components/buttons.module.css';
import cart from '../components/cart.module.css';
import { ConfirmDialog } from '../components/ConfirmDialog.tsx';
import notices from '../components/notices.module.css';
import orders from '../components/orders.module.css';
import { OrderSummary } from '../components/OrderSummary.tsx';
import { PaymentCountdown } from '../components/PaymentCountdown.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { StatusHistory } from '../components/StatusHistory.tsx';
import { cx } from '../cx.ts';
import { deliveryAddressText } from '../format.ts';
import {
  cancellationLabel,
  declineLabel,
  orderStatusLabel,
  paymentStatusLabel,
} from '../labels.ts';

function PaymentOutcome({ view, order }: { view: OrderView; order: Order }): JSX.Element | null {
  const failed = view.paymentStatus === 'failed';
  const attempt = usePaymentAttempt(order.paymentAttemptId, failed);
  if (view.paymentDeadline !== undefined)
    return <PaymentCountdown deadline={view.paymentDeadline} />;
  if (!failed) return null;
  if (attempt.isPending && typeof order.paymentAttemptId === 'string') {
    return <p role="status">The payment failed. Looking up the reason…</p>;
  }
  return (
    <p className={cx(notices.notice, notices.danger)} role="alert">
      The payment failed: {declineLabel(attempt.data?.declineReason)}. Nothing was charged.
    </p>
  );
}

function CancelOrder({ view, onRefresh }: { view: OrderView; onRefresh: () => void }): JSX.Element {
  const cancel = useCancelOrder();
  const [confirming, setConfirming] = useState(false);
  const [cancelled, setCancelled] = useState(false);
  const [refusal, setRefusal] = useState<string | undefined>(undefined);
  const [failure, setFailure] = useState<{ readonly error: unknown } | undefined>(undefined);
  const offered = view.actions.some((action) => action.kind === 'cancel');

  const confirm = async (): Promise<void> => {
    correlation.next();
    setRefusal(undefined);
    setFailure(undefined);
    try {
      const result = await cancel.mutateAsync(view.id);
      if (result.kind === 'cancelled') setCancelled(true);
      else {
        setRefusal(
          result.kind === 'notCancellable' ? result.message : 'This order no longer exists.',
        );
      }
    } catch (error: unknown) {
      setFailure({ error });
    } finally {
      setConfirming(false);
    }
  };

  return (
    <div className={orders.section}>
      {cancelled ? <p role="status">The order was cancelled.</p> : null}
      {refusal === undefined ? null : (
        <div role="alert">
          <p>{refusal}</p>
          <p className={orders.hint}>The status shown on this page may be out of date.</p>
          <button
            className={cx(buttons.button, buttons.secondary)}
            type="button"
            onClick={onRefresh}
          >
            Refresh the order
          </button>
        </div>
      )}
      {failure === undefined ? null : (
        <ActionError
          error={failure.error}
          title="The order was not cancelled"
          onDismiss={() => {
            setFailure(undefined);
          }}
        />
      )}
      {offered ? (
        <p className={styles.actions}>
          <button
            className={cx(buttons.button, buttons.danger)}
            type="button"
            disabled={cancel.isPending}
            onClick={() => {
              setConfirming(true);
            }}
          >
            Cancel order
          </button>
        </p>
      ) : null}
      <ConfirmDialog
        open={confirming}
        title="Cancel this order?"
        description="Stock is returned and a payment already approved is refunded. This cannot be undone."
        confirmLabel="Yes, cancel the order"
        cancelLabel="Keep the order"
        destructive
        busy={cancel.isPending}
        onConfirm={() => {
          void confirm();
        }}
        onCancel={() => {
          setConfirming(false);
        }}
      />
    </div>
  );
}

function OrderDetail({
  order,
  onRefresh,
}: {
  readonly order: Order;
  readonly onRefresh: () => void;
}): JSX.Element {
  const view = toOrderView(order);
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Order {view.number}
      </h1>
      <p>
        Order id <code className={cart.code}>{view.id}</code>
      </p>
      <dl className={cart.statuses}>
        <dt>Order status</dt>
        <dd>
          {orderStatusLabel(view.orderStatus)}
          {view.cancellationReason === undefined
            ? ''
            : ` (${cancellationLabel(view.cancellationReason)})`}
        </dd>
        <dt>Payment status</dt>
        <dd>{paymentStatusLabel(view.paymentStatus)}</dd>
      </dl>
      <PaymentOutcome view={view} order={order} />
      <OrderSummary
        caption="Items"
        lines={view.lines.map((line) => ({
          key: line.productId,
          name: line.name,
          quantity: line.quantity,
          unitPrice: line.unitPrice,
          lineTotal: line.lineTotal,
        }))}
        total={view.total}
      />
      <p>Delivery to {deliveryAddressText(view.deliveryAddress)}.</p>
      <div className={orders.section}>
        <h2 className={orders.heading}>Status history</h2>
        <StatusHistory entries={view.history} />
      </div>
      <CancelOrder view={view} onRefresh={onRefresh} />
      <p className={styles.actions}>
        <Link className={buttons.link} to="/orders">
          Back to your orders
        </Link>
      </p>
    </section>
  );
}

/**
 * `/orders/:id` (FR-009, FR-010): lines, amounts, address, both statuses and the history with its
 * actors (never an account id); the awaiting-payment countdown while the payment is pending (the
 * order is refreshed every 5 s until the payment is final or its window ends); cancel offered only
 * while the order is `placed`, after a confirmation, with the platform's refusal shown as is.
 */
export function OrderPage(): JSX.Element {
  const { id } = useParams();
  const orderId = id !== undefined && isUuid(id) ? id.toLowerCase() : undefined;
  const order = useOwnOrder(orderId);
  if (orderId === undefined) return <NotFoundPage />;
  return (
    <QueryBoundary query={order} loadingLabel="Loading your order…" loading="lines">
      {(found) =>
        found === null ? (
          <NotFoundPage />
        ) : (
          <OrderDetail
            order={found}
            onRefresh={() => {
              void order.refetch();
            }}
          />
        )
      }
    </QueryBoundary>
  );
}
