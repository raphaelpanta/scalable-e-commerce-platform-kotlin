import type { JSX } from 'react';

import styles from './brand.module.css';
import { BRAND_NAME } from './brand.ts';
import { BrandMark } from './BrandMark.tsx';

/** The mark followed by the store name in the display face. */
export function Wordmark(): JSX.Element {
  return (
    <span className={styles.wordmark}>
      <BrandMark />
      <span className={styles.name}>{BRAND_NAME}</span>
    </span>
  );
}
