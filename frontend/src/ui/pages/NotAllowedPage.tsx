import type { JSX } from 'react';
import { Link } from 'react-router';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';

/** The "not allowed" state of the console for a signed-in account without the operator role. */
export function NotAllowedPage(): JSX.Element {
  return (
    <section className={styles.page} aria-labelledby="not-allowed-title">
      <h1 id="not-allowed-title" className={styles.title}>
        Not allowed
      </h1>
      <p>The console is available to operators only.</p>
      <p className={styles.actions}>
        <Link className={buttons.button} to="/">
          Back to the store
        </Link>
      </p>
    </section>
  );
}
