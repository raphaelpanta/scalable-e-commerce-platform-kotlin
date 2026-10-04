import type { JSX } from 'react';

import styles from './states.module.css';

export type LoadingProps = {
  readonly label?: string;
};

/** Loading state (FR-016): announced through `role="status"`, no layout shift. */
export function Loading({ label = 'Loading…' }: LoadingProps): JSX.Element {
  return (
    <div className={styles.state} role="status" aria-live="polite">
      <span className={styles.spinner} aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
