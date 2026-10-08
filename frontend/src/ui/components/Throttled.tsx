import { type JSX, useEffect, useState } from 'react';

import buttons from './buttons.module.css';
import styles from './notices.module.css';

export type ThrottledProps = {
  readonly retryAfterSeconds: number;
  readonly onRetry: () => void;
  readonly title?: string;
};

function wholeSeconds(seconds: number): number {
  return Math.max(0, Math.ceil(seconds));
}

function useCountdown(seconds: number): number {
  const [remaining, setRemaining] = useState(() => wholeSeconds(seconds));
  useEffect(() => {
    const total = wholeSeconds(seconds);
    if (total === 0) return undefined;
    const deadline = Date.now() + total * 1000;
    const timer = setInterval(() => {
      const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      setRemaining(left);
      if (left === 0) clearInterval(timer);
    }, 1000);
    return () => {
      clearInterval(timer);
    };
  }, [seconds]);
  return remaining;
}

/** Throttled state (FR-016): a live countdown from `Retry-After`; retry disabled until it ends. */
export function Throttled({
  retryAfterSeconds,
  onRetry,
  title = 'Too many requests',
}: ThrottledProps): JSX.Element {
  const remaining = useCountdown(retryAfterSeconds);
  const canRetry = remaining === 0;
  return (
    <div className={styles.notice} role="status" aria-live="polite">
      <h2 className={styles.title}>{title}</h2>
      <p className={styles.message}>
        {canRetry ? (
          'You can try again now.'
        ) : (
          <>
            Try again in <span className={styles.countdown}>{remaining}</span>{' '}
            {remaining === 1 ? 'second' : 'seconds'}.
          </>
        )}
      </p>
      <button className={buttons.button} type="button" disabled={!canRetry} onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}
