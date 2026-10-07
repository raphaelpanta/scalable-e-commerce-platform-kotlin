import type { JSX } from 'react';
import { Link } from 'react-router';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';

export type NotAllowedPageProps = {
  /** The platform's own words when it was the platform that refused (a 403 problem `detail`). */
  readonly detail?: string;
};

/**
 * The "not allowed" state of the console for a signed-in account without the operator role: no
 * console content, only the way back. When the platform itself answered 403 its explanation is
 * shown too, still without any data (FR-012).
 */
export function NotAllowedPage({ detail }: NotAllowedPageProps): JSX.Element {
  return (
    <section className={styles.page} aria-labelledby="not-allowed-title">
      <h1 id="not-allowed-title" className={styles.title}>
        Not allowed
      </h1>
      <p>The console is available to operators only.</p>
      {detail === undefined ? null : <p>The platform refused the request: {detail}</p>}
      <p className={styles.actions}>
        <Link className={buttons.button} to="/">
          Back to the store
        </Link>
      </p>
    </section>
  );
}
