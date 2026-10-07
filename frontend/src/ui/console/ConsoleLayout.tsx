import type { JSX } from 'react';
import { NavLink, Outlet } from 'react-router';

import styles from './console.module.css';
import { cx } from '../cx.ts';

/** What the console says about catalogue editing, on every console page (FR-011, US6 scenario 6). */
export const CATALOGUE_EDITING_NOTE =
  'Catalogue editing (creating, editing, withdrawing and reinstating products and categories) is ' +
  'done through the API for now; this console only fulfils orders and adjusts stock.';

/**
 * The frame of the operator console: its own navigation (orders, stock), the page, and the
 * sentence that catalogue editing is API-only. It renders only inside the operator-protected
 * routes; the platform authorises every request regardless (FR-012).
 */
export function ConsoleLayout(): JSX.Element {
  return (
    <div className={styles.console}>
      <nav className={styles.nav} aria-label="Console">
        <NavLink className={cx(styles.navLink)} to="/console/orders">
          Orders
        </NavLink>
        <NavLink className={cx(styles.navLink)} to="/console/stock">
          Stock
        </NavLink>
      </nav>
      <Outlet />
      <p className={styles.note}>{CATALOGUE_EDITING_NOTE}</p>
    </div>
  );
}
