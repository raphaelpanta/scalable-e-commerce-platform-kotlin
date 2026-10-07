import type { JSX } from 'react';

import { ThrottledError } from '@api/problem';

import { describeError, ErrorState } from './ErrorState.tsx';
import { Throttled } from './Throttled.tsx';

export type ActionErrorProps = {
  readonly error: unknown;
  readonly title: string;
  /** Clears the failure so the shopper can try the action again. */
  readonly onDismiss: () => void;
};

/**
 * The error and throttled states of an action (FR-016): a readable message with the support
 * reference and a retry that simply clears it, or the countdown of a 429 with the retry disabled
 * until it ends. Never raw problem JSON, never an automatic retry.
 */
export function ActionError({ error, title, onDismiss }: ActionErrorProps): JSX.Element {
  if (error instanceof ThrottledError) {
    return <Throttled retryAfterSeconds={error.retryAfterSeconds} onRetry={onDismiss} />;
  }
  return <ErrorState title={title} {...describeError(error)} onRetry={onDismiss} />;
}
