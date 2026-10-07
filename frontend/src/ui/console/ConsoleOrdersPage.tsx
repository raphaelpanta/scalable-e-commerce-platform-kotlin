import { type JSX, useId } from 'react';
import { useSearchParams } from 'react-router';

import { DEFAULT_PAGE_SIZE } from '@app/catalog/browseParams';
import { filterFromSearch, withStatus } from '@app/console/consoleOrders';
import { useConsoleOrders } from '@app/console/useConsole';
import { ORDER_STATUSES, OrderStatus } from '@domain/status';

import styles from './console.module.css';
import { ConsolePager } from './ConsolePager.tsx';
import { OrdersTable } from './OrdersTable.tsx';
import { platformRefusal } from './refusal.ts';
import { Empty } from '../components/Empty.tsx';
import forms from '../components/forms.module.css';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import { orderStatusLabel } from '../labels.ts';
import { NotAllowedPage } from '../pages/NotAllowedPage.tsx';
import pages from '../pages/pages.module.css';

/**
 * `/console/orders`: every shopper's orders, newest first, with both statuses and the platform's
 * `orderStatus` filter; the status, page and size live in the address (FR-003, FR-011). A 403 from
 * the platform renders the "not allowed" state and none of the data.
 */
export function ConsoleOrdersPage(): JSX.Element {
  const selectId = useId();
  const [searchParams, setSearchParams] = useSearchParams();
  const filter = filterFromSearch(searchParams);
  const orders = useConsoleOrders(filter);
  const refusal = platformRefusal(orders.error);
  if (refusal !== undefined)
    return <NotAllowedPage {...(refusal.detail === undefined ? {} : { detail: refusal.detail })} />;

  return (
    <section className={pages.page} aria-labelledby="page-title">
      <h1 id="page-title" className={pages.title}>
        Orders
      </h1>
      <div className={styles.toolbar}>
        <div className={styles.control}>
          <label className={forms.label} htmlFor={selectId}>
            Filter by status
          </label>
          <select
            id={selectId}
            className={forms.input}
            value={filter.status ?? ''}
            onChange={(event) => {
              const chosen = event.target.value;
              setSearchParams(
                withStatus(searchParams, OrderStatus.isOrderStatus(chosen) ? chosen : undefined),
              );
            }}
          >
            <option value="">All statuses</option>
            {ORDER_STATUSES.map((status) => (
              <option key={status} value={status}>
                {orderStatusLabel(status)}
              </option>
            ))}
          </select>
        </div>
      </div>
      <QueryBoundary query={orders} loadingLabel="Loading orders…">
        {(page) =>
          page.items.length === 0 ? (
            filter.status === undefined ? (
              <Empty
                title="No orders"
                message="No order has been placed yet."
                action={{ label: 'Back to the store', to: '/' }}
              />
            ) : (
              <Empty
                title="No orders"
                message={`No order is ${orderStatusLabel(filter.status).toLowerCase()} right now.`}
                action={{ label: 'Show all orders', to: '/console/orders' }}
              />
            )
          ) : (
            <>
              <OrdersTable orders={page.items} />
              <ConsolePager
                page={page.page}
                size={page.size > 0 ? page.size : (filter.size ?? DEFAULT_PAGE_SIZE)}
                totalItems={page.totalItems}
                shown={page.items.length}
                noun="orders"
              />
            </>
          )
        }
      </QueryBoundary>
    </section>
  );
}
