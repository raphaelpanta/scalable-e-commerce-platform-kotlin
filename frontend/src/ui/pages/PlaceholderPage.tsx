import type { JSX } from 'react';

import styles from './pages.module.css';

export type PlaceholderPageProps = {
  readonly title: string;
};

/**
 * Stands in for a page that a later phase implements (US1–US6), so every route of
 * contracts/storefront-routes.md resolves and the shell can be tested end to end.
 */
export function PlaceholderPage({ title }: PlaceholderPageProps): JSX.Element {
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        {title}
      </h1>
      <p>This page is coming soon.</p>
    </section>
  );
}
