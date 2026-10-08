import type { JSX } from 'react';

import { cx } from '../cx.ts';
import styles from './states.module.css';

export type LoadingVariant = 'spinner' | 'grid' | 'detail' | 'lines';

export type LoadingProps = {
  readonly label?: string;
  /** `spinner` (default) for small areas; skeletons reserve the shape of what is arriving. */
  readonly variant?: LoadingVariant;
};

const GRID_CARDS = [0, 1, 2, 3, 4, 5, 6, 7] as const;
const LINE_ROWS = [0, 1, 2] as const;

function Skeleton({
  variant,
}: {
  readonly variant: Exclude<LoadingVariant, 'spinner'>;
}): JSX.Element {
  if (variant === 'grid') {
    return (
      <div className={styles.skeletonGrid} aria-hidden="true">
        {GRID_CARDS.map((card) => (
          <div key={card} className={styles.skeletonCard}>
            <span className={cx(styles.skeleton, styles.skeletonImage)} />
            <span className={cx(styles.skeleton, styles.skeletonLine)} />
            <span className={cx(styles.skeleton, styles.skeletonLine, styles.skeletonShort)} />
          </div>
        ))}
      </div>
    );
  }
  if (variant === 'detail') {
    return (
      <div className={styles.skeletonDetail} aria-hidden="true">
        <span className={cx(styles.skeleton, styles.skeletonDetailImage)} />
        <div className={styles.skeletonLines}>
          <span className={cx(styles.skeleton, styles.skeletonTitle)} />
          <span className={cx(styles.skeleton, styles.skeletonLine, styles.skeletonShort)} />
          <span className={cx(styles.skeleton, styles.skeletonLine)} />
          <span className={cx(styles.skeleton, styles.skeletonLine)} />
        </div>
      </div>
    );
  }
  return (
    <div className={styles.skeletonLines} aria-hidden="true">
      {LINE_ROWS.map((row) => (
        <span key={row} className={cx(styles.skeleton, styles.skeletonRow)} />
      ))}
    </div>
  );
}

/** Loading state (FR-016): announced through `role="status"`, skeleton blocks reserve the layout. */
export function Loading({ label = 'Loading…', variant = 'spinner' }: LoadingProps): JSX.Element {
  if (variant === 'spinner') {
    return (
      <div className={styles.state} role="status" aria-live="polite">
        <span className={styles.spinner} aria-hidden="true" />
        <span>{label}</span>
      </div>
    );
  }
  return (
    <div className={styles.loading} role="status" aria-live="polite">
      <Skeleton variant={variant} />
      <span className={styles.srOnly}>{label}</span>
    </div>
  );
}
