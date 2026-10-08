import type { JSX } from 'react';
import { Link } from 'react-router';

import buttons from './buttons.module.css';
import styles from './states.module.css';

export type EmptyAction =
  | { readonly label: string; readonly to: string }
  | { readonly label: string; readonly onClick: () => void };

export type EmptyProps = {
  readonly title: string;
  readonly message?: string;
  /** Exactly one next action (FR-016). */
  readonly action: EmptyAction;
};

/** Empty state: an explanation and one next action. */
export function Empty({ title, message, action }: EmptyProps): JSX.Element {
  return (
    <div className={styles.state}>
      <span className={styles.mark} aria-hidden="true" />
      <h2 className={styles.title}>{title}</h2>
      {message === undefined ? null : <p className={styles.message}>{message}</p>}
      {'to' in action ? (
        <Link className={buttons.button} to={action.to}>
          {action.label}
        </Link>
      ) : (
        <button className={buttons.button} type="button" onClick={action.onClick}>
          {action.label}
        </button>
      )}
    </div>
  );
}
