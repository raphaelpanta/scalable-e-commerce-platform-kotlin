import type { JSX } from 'react';

import styles from './brand.module.css';

/** The Vibestore mark: a rounded tile with a leaf cut-out. Decorative; the name carries the meaning. */
export function BrandMark(): JSX.Element {
  return (
    <svg
      className={styles.mark}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect className={styles.markBody} x="2" y="2" width="20" height="20" rx="6" />
      <path className={styles.markLeaf} d="M7 17C7 10.500 10.500 7 17 7c0 6.500-3.500 10-10 10z" />
    </svg>
  );
}
