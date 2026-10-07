import type { JSX } from 'react';
import { Link, useSearchParams } from 'react-router';

import { pageCount, withPage } from '@app/catalog/browseParams';

import buttons from '../components/buttons.module.css';
import pager from '../components/Pager.module.css';
import { cx } from '../cx.ts';

export type ConsolePagerProps = {
  /** Zero-based page index as the platform reports it. */
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
  /** Items on this page. */
  readonly shown: number;
  /** What the list holds, for the summary line ("orders", "products"). */
  readonly noun: string;
};

function searchFor(current: URLSearchParams, page: number): string {
  const search = withPage(current, page).toString();
  return search === '' ? '' : `?${search}`;
}

/**
 * Page controls of a console list, driven by the URL (`?page=` zero-based, `?size=`, `?status=` or
 * `?q=` kept as they are, FR-003). A link exists only where a page exists.
 */
export function ConsolePager({
  page,
  size,
  totalItems,
  shown,
  noun,
}: ConsolePagerProps): JSX.Element {
  const [searchParams] = useSearchParams();
  const pages = pageCount(totalItems, size);
  const control = (label: string, target: number, available: boolean): JSX.Element =>
    available ? (
      <Link
        className={cx(buttons.button, buttons.secondary)}
        to={{ search: searchFor(searchParams, target) }}
      >
        {label}
      </Link>
    ) : (
      <span className={cx(buttons.button, buttons.secondary, pager.inert)} aria-disabled="true">
        {label}
      </span>
    );
  return (
    <nav className={pager.pager} aria-label="Pagination">
      <p className={pager.summary}>
        Showing {shown} of {totalItems} {noun}
      </p>
      <p className={pager.position}>
        Page {page + 1} of {pages}
      </p>
      <div className={pager.controls}>
        {control('Previous page', page - 1, page > 0)}
        {control('Next page', page + 1, page + 1 < pages)}
      </div>
    </nav>
  );
}
