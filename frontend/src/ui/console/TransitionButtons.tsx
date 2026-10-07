import { type JSX, useState } from 'react';

import { consoleActions, targetOf } from '@app/console/consoleOrders';
import type { TransitionResult } from '@app/console/consolePort';
import { useTransition } from '@app/console/useConsole';
import type { Order } from '@app/order/orderPort';
import { orderNumber } from '@app/order/orderView';
import type { OrderAction } from '@domain/status';

import styles from './console.module.css';
import buttons from '../components/buttons.module.css';
import { ConfirmDialog } from '../components/ConfirmDialog.tsx';
import { describeError } from '../components/ErrorState.tsx';
import { cx } from '../cx.ts';
import { orderStatusLabel } from '../labels.ts';

const ADVANCE_LABELS = {
  preparing: 'Start preparing',
  shipped: 'Mark as shipped',
  delivered: 'Mark as delivered',
} as const;

function labelOf(action: OrderAction): string {
  return action.kind === 'cancel' ? 'Cancel order' : ADVANCE_LABELS[action.to];
}

function titleOf(action: OrderAction, number: string): string {
  if (action.kind === 'cancel') return `Cancel order ${number}?`;
  switch (action.to) {
    case 'preparing':
      return `Start preparing order ${number}?`;
    case 'shipped':
      return `Mark order ${number} as shipped?`;
    case 'delivered':
      return `Mark order ${number} as delivered?`;
  }
}

function descriptionOf(action: OrderAction): string {
  if (action.kind === 'cancel') {
    return 'The order is cancelled for good. A payment that was already approved is refunded.';
  }
  return action.to === 'preparing'
    ? 'The order moves to preparation.'
    : 'The shopper is notified of the change.';
}

type Outcome =
  | { readonly kind: 'moved'; readonly text: string }
  | { readonly kind: 'refused'; readonly text: string };

function outcomeOf(result: TransitionResult, number: string): Outcome {
  switch (result.kind) {
    case 'transitioned':
      return {
        kind: 'moved',
        text: `Order ${number} is now ${orderStatusLabel(result.order.orderStatus)}.`,
      };
    case 'refused':
      return { kind: 'refused', text: result.message };
    case 'notFound':
      return { kind: 'refused', text: 'This order no longer exists.' };
    case 'forbidden':
      return {
        kind: 'refused',
        text: 'The platform refused this action: only operators can change an order.',
      };
  }
}

export type TransitionButtonsProps = {
  readonly order: Order;
};

/**
 * The transitions an operator may make on an order: only those the lifecycle allows for its two
 * statuses (data-model.md §3.3), each behind a confirmation (FR-011). A refusal by the platform
 * (409 `invalid-transition`, 403) is explained and the displayed order stays as it was; a success
 * replaces the order everywhere it is shown.
 */
export function TransitionButtons({ order }: TransitionButtonsProps): JSX.Element {
  const actions = consoleActions(order);
  const number = orderNumber(order.id);
  const transition = useTransition(order.id);
  const [asking, setAsking] = useState<OrderAction | undefined>(undefined);
  const [outcome, setOutcome] = useState<Outcome | undefined>(undefined);
  const waitingForPayment = order.orderStatus === 'placed' && order.paymentStatus === 'pending';

  const confirm = async (): Promise<void> => {
    if (asking === undefined) return;
    try {
      setOutcome(outcomeOf(await transition.mutateAsync(targetOf(asking)), number));
    } catch (error) {
      setOutcome({ kind: 'refused', text: describeError(error).message ?? 'Please try again.' });
    }
    setAsking(undefined);
  };

  return (
    <div className={styles.panel}>
      {actions.length === 0 ? null : (
        <div role="group" aria-label="Order actions" className={styles.actions}>
          {actions.map((action) => (
            <button
              key={action.kind === 'cancel' ? 'cancel' : action.to}
              className={cx(buttons.button, action.kind === 'cancel' && buttons.secondary)}
              type="button"
              disabled={transition.isPending}
              onClick={() => {
                setOutcome(undefined);
                setAsking(action);
              }}
            >
              {labelOf(action)}
            </button>
          ))}
        </div>
      )}
      {waitingForPayment ? <p>This order can be prepared once its payment is approved.</p> : null}
      {outcome === undefined ? null : outcome.kind === 'moved' ? (
        <p role="status" className={styles.message}>
          {outcome.text}
        </p>
      ) : (
        <p role="alert" className={cx(styles.message, styles.refusal)}>
          {outcome.text}
        </p>
      )}
      {asking === undefined ? null : (
        <ConfirmDialog
          open
          title={titleOf(asking, number)}
          description={descriptionOf(asking)}
          confirmLabel={asking.kind === 'cancel' ? 'Cancel the order' : labelOf(asking)}
          cancelLabel={asking.kind === 'cancel' ? 'Keep the order' : 'Cancel'}
          destructive={asking.kind === 'cancel'}
          busy={transition.isPending}
          onConfirm={() => {
            void confirm();
          }}
          onCancel={() => {
            setAsking(undefined);
          }}
        />
      )}
    </div>
  );
}
