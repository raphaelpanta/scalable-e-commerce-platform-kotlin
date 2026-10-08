import type { JSX } from 'react';

import { ApiError } from '@api/problem';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import styles from './states.module.css';

export type ErrorStateProps = {
  readonly title?: string;
  /** Readable message; from the problem `title`/`detail` after mapping, never raw JSON. */
  readonly message?: string;
  readonly correlationId?: string;
  readonly onRetry?: () => void;
  readonly retryLabel?: string;
};

/** Derives the shopper-facing fields from an unknown error (typed `ApiError` or anything else). */
export function describeError(error: unknown): Pick<ErrorStateProps, 'message' | 'correlationId'> {
  if (error instanceof ApiError) {
    const { problem } = error;
    return {
      message: problem.detail ?? problem.title,
      ...(problem.correlationId === undefined ? {} : { correlationId: problem.correlationId }),
    };
  }
  return { message: 'Something went wrong. Please try again.' };
}

/** Error state (FR-016): a readable message, a retry action and the correlation id in a collapsible. */
export function ErrorState({
  title = 'Something went wrong',
  message = 'Please try again.',
  correlationId,
  onRetry,
  retryLabel = 'Try again',
}: ErrorStateProps): JSX.Element {
  return (
    <div className={cx(styles.state, styles.danger)} role="alert">
      <span className={styles.mark} aria-hidden="true" />
      <h2 className={styles.title}>{title}</h2>
      <p className={styles.message}>{message}</p>
      <p className={styles.message}>That did not go as planned.</p>
      {onRetry === undefined ? null : (
        <button className={buttons.button} type="button" onClick={onRetry}>
          {retryLabel}
        </button>
      )}
      {correlationId === undefined ? null : (
        <details className={styles.details}>
          <summary>Support details</summary>
          <p>
            Reference: <code className={styles.code}>{correlationId}</code>
          </p>
        </details>
      )}
    </div>
  );
}
