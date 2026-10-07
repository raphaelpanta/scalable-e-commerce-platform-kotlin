import type { JSX } from 'react';
import { Link, useSearchParams } from 'react-router';

import { pageCount, withPage } from '@app/catalog/browseParams';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import styles from './Pager.module.css';

export type PagerProps = {
  /** Zero-based page index as the platform reports it. */
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
  /** Items on this page. */
  readonly shown: number;
  /** What the list holds, singular and plural ("product" and "products" by default). */
  readonly noun?: { readonly singular: string; readonly plural: string };
};

function searchFor(current: URLSearchParams, page: number): string {
  const search = withPage(current, page).toString();
  return search === '' ? '' : `?${search}`;
}

/**
 * Page controls driven by the URL (`?page=` zero-based, `?size=` kept as it is, FR-003). A link
 * exists only where a page exists; the other control is an inert, announced placeholder.
 */
export function Pager({
  page,
  size,
  totalItems,
  shown,
  noun = { singular: 'product', plural: 'products' },
}: PagerProps): JSX.Element {
  const [searchParams] = useSearchParams();
  const pages = pageCount(totalItems, size);
  const hasPrevious = page > 0;
  const hasNext = page + 1 < pages;
  return (
    <nav className={styles.pager} aria-label="Pagination">
      <p className={styles.summary}>
        Showing {shown} of {totalItems} {totalItems === 1 ? noun.singular : noun.plural}
      </p>
      <p className={styles.position}>
        Page {page + 1} of {pages}
      </p>
      <div className={styles.controls}>
        {hasPrevious ? (
          <Link
            className={cx(buttons.button, buttons.secondary)}
            to={{ search: searchFor(searchParams, page - 1) }}
          >
            Previous page
          </Link>
        ) : (
          <span
            className={cx(buttons.button, buttons.secondary, styles.inert)}
            aria-disabled="true"
          >
            Previous page
          </span>
        )}
        {hasNext ? (
          <Link
            className={cx(buttons.button, buttons.secondary)}
            to={{ search: searchFor(searchParams, page + 1) }}
          >
            Next page
          </Link>
        ) : (
          <span
            className={cx(buttons.button, buttons.secondary, styles.inert)}
            aria-disabled="true"
          >
            Next page
          </span>
        )}
      </div>
    </nav>
  );
}
