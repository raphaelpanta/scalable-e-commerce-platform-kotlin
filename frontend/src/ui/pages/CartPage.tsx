import { type JSX, useState } from 'react';
import { Link } from 'react-router';

import { useCart } from '@app/cart/useCart';
import { correlation } from '@app/correlation';
import { Quantity } from '@domain/quantity';

import styles from './pages.module.css';
import buttons from '../components/buttons.module.css';
import cart from '../components/cart.module.css';
import { CartLine } from '../components/CartLine.tsx';
import { Empty } from '../components/Empty.tsx';
import { describeError } from '../components/ErrorState.tsx';
import { Money } from '../components/Money.tsx';
import { QueryBoundary } from '../components/QueryBoundary.tsx';
import states from '../components/states.module.css';
import { cx } from '../cx.ts';

/**
 * `/cart` (FR-004): the lines with their current prices and the server's totals, quantity changes
 * (optimistic, rolled back when refused), removals, the price-change and unavailable flags, and
 * the way to checkout (which the route guard turns into a sign-in for an anonymous visitor).
 */
export function CartPage(): JSX.Element {
  const { view, query, actions } = useCart();
  const [busyLine, setBusyLine] = useState<string | undefined>(undefined);
  const [failure, setFailure] = useState<{ lineId: string; message: string } | undefined>(
    undefined,
  );

  const change = async (lineId: string, work: () => Promise<unknown>): Promise<void> => {
    correlation.next();
    setBusyLine(lineId);
    setFailure(undefined);
    try {
      await work();
    } catch (error: unknown) {
      setFailure({
        lineId,
        message: describeError(error).message ?? 'The change was not applied.',
      });
    } finally {
      setBusyLine(undefined);
    }
  };

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <h1 id="page-title" className={styles.title}>
        Your cart
      </h1>
      <QueryBoundary query={query} loadingLabel="Loading your cart…">
        {() =>
          view.isEmpty ? (
            <Empty
              title="Your cart is empty"
              message="Add a product to get started."
              action={{ label: 'Browse products', to: '/' }}
            />
          ) : (
            <>
              {failure === undefined ? null : (
                <p className={cx(states.state, states.danger)} role="alert">
                  {failure.message}
                </p>
              )}
              <ul className={cart.lines} aria-label="Cart lines">
                {view.lines.map((line) => (
                  <CartLine
                    key={line.id}
                    line={line}
                    busy={busyLine === line.id}
                    onQuantity={(lineId, quantity) => {
                      void change(lineId, () => actions.setQuantity(lineId, quantity));
                    }}
                    onRemove={(lineId) => {
                      void change(lineId, () => actions.setQuantity(lineId, Quantity.zero));
                    }}
                  />
                ))}
              </ul>
              <p className={cart.total}>
                <span>Total</span>
                {view.total === undefined ? null : <Money value={view.total} />}
              </p>
              {view.canCheckout ? (
                <p className={styles.actions}>
                  <Link className={buttons.button} to="/checkout">
                    Check out
                  </Link>
                  <Link className={buttons.link} to="/">
                    Continue shopping
                  </Link>
                </p>
              ) : (
                <p className={styles.actions}>
                  <span className={cx(buttons.button, states.inert)} aria-disabled="true">
                    Check out
                  </span>
                  <span className={cart.unavailable}>
                    Remove the unavailable items to check out.
                  </span>
                </p>
              )}
            </>
          )
        }
      </QueryBoundary>
    </section>
  );
}
