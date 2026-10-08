import { type JSX, useEffect, useState } from 'react';

import { formatRemaining, remainingUntil } from '@app/order/orderView';

import { cx } from '../cx.ts';
import styles from './notices.module.css';

export type PaymentCountdownProps = {
  /** The order's `paymentExpiresAt`, never recomputed in the browser (FR-009). */
  readonly deadline: Date;
};

const TICK_MS = 1000;

/** "Awaiting payment" with the time left before the payment window ends, ticking every second. */
export function PaymentCountdown({ deadline }: PaymentCountdownProps): JSX.Element {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(new Date());
    }, TICK_MS);
    return () => {
      clearInterval(timer);
    };
  }, []);
  const remaining = remainingUntil(deadline, now);
  const ended = remaining.minutes === 0 && remaining.seconds === 0;
  return (
    <p
      className={cx(styles.notice, styles.warning)}
      role="timer"
      aria-live="polite"
      aria-atomic="true"
    >
      {ended ? (
        'The payment window has ended; the order is being cancelled.'
      ) : (
        <>
          Awaiting payment: <span className={styles.countdown}>{formatRemaining(remaining)}</span>{' '}
          left before the payment window ends.
        </>
      )}
    </p>
  );
}
