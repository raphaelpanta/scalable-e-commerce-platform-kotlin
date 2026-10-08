import type { JSX, ReactNode } from 'react';
import { useSearchParams } from 'react-router';

import { listingFromSearch, withPage } from '@app/catalog/browseParams';
import { toOrderView } from '@app/order/orderView';
import { useOwnOrders } from '@app/order/useOrders';

import styles from './pages.module.css';
import { Empty } from '../components/Empty.tsx';
import { OrderRow } from '../components/OrderRow.tsx';
import orders from '../components/orders.module.css';
import { Pager } from '../components/Pager.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';

/**
 * `/orders` (FR-010): the shopper's own orders, newest first as the platform lists them, each with
 * order status, payment status, total and date; `?page=` and `?size=` live in the address
 * (FR-003). Loading, empty, error and throttled are the shared states (FR-016).
 */
export function OrdersPage(): JSX.Element {
  const [searchParams] = useSearchParams();
  const query = useOwnOrders(listingFromSearch(searchParams));
  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Your orders
      </h1>
      <QueryBoundary query={query} loadingLabel="Loading your orders…" loading="lines">
        {(page): ReactNode => {
          if (page.items.length === 0) {
            if (page.page > 0) {
              const first = withPage(searchParams, 0).toString();
              return (
                <Empty
                  title="No more orders"
                  message="This page is past the end of the list."
                  action={{ label: 'Back to the first page', to: first === '' ? '.' : `?${first}` }}
                />
              );
            }
            return (
              <Empty
                title="No orders yet"
                message="The orders you place appear here, with their status. Your first one will land here."
                action={{ label: 'Browse products', to: '/' }}
              />
            );
          }
          return (
            <>
              <ul className={orders.list} aria-label="Your orders">
                {page.items.map((order) => (
                  <OrderRow key={order.id} order={toOrderView(order)} />
                ))}
              </ul>
              <Pager
                page={page.page}
                size={page.size}
                totalItems={page.totalItems}
                shown={page.items.length}
                noun={{ singular: 'order', plural: 'orders' }}
              />
            </>
          );
        }}
      </QueryBoundary>
    </section>
  );
}
