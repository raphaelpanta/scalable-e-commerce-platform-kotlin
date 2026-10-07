import { type JSX, useId } from 'react';
import { Link, NavLink } from 'react-router';

import { categoryPath } from '@app/catalog/browseParams';
import { useCategories } from '@app/catalog/useCategories';

import { cx } from '../cx.ts';
import styles from './CategoryNav.module.css';
import { QueryBoundary } from './QueryBoundary.tsx';

export type CategoryNavProps = {
  /** The category being browsed, marked `aria-current="page"`. */
  readonly currentId?: string | undefined;
  /** The browsed category itself, listed even when it falls outside the page of categories fetched. */
  readonly current?: { readonly id: string; readonly name: string } | null | undefined;
};

/** The categories offered for browsing on every browsing page (FR-001), with its own states. */
export function CategoryNav({ currentId, current }: CategoryNavProps): JSX.Element {
  const headingId = useId();
  const categories = useCategories();
  return (
    <nav className={styles.nav} aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.heading}>
        Categories
      </h2>
      <QueryBoundary query={categories} loadingLabel="Loading categories…">
        {(page) => (
          <ul className={styles.list}>
            <li>
              <NavLink className={cx(styles.link)} to="/" end>
                All products
              </NavLink>
            </li>
            {page.items.map((category) => (
              <li key={category.id}>
                <Link
                  className={styles.link}
                  to={categoryPath(category)}
                  aria-current={category.id === currentId ? 'page' : undefined}
                >
                  {category.name}
                </Link>
              </li>
            ))}
            {current && !page.items.some((category) => category.id === current.id) ? (
              <li key={current.id}>
                <Link className={styles.link} to={categoryPath(current)} aria-current="page">
                  {current.name}
                </Link>
              </li>
            ) : null}
            {page.items.length === 0 ? <li className={styles.empty}>No categories yet.</li> : null}
          </ul>
        )}
      </QueryBoundary>
    </nav>
  );
}
