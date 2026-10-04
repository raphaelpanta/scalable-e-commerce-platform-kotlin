import type { JSX } from 'react';
import { Link } from 'react-router';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';

/** Not-found page (FR-002): rendered inside the shell for any unknown path or unknown item. */
export function NotFoundPage(): JSX.Element {
  return (
    <section className={styles.page} aria-labelledby="not-found-title">
      <h1 id="not-found-title" className={styles.title}>
        Page not found
      </h1>
      <p>The page or item you asked for does not exist or is no longer available.</p>
      <p className={styles.actions}>
        <Link className={buttons.button} to="/">
          Browse products
        </Link>
        <Link className={buttons.link} to="/search">
          Search the store
        </Link>
      </p>
    </section>
  );
}
