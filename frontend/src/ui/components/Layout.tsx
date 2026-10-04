import { type JSX, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';

import { correlation } from '@app/correlation';
import { signInLocationFor } from '@app/navigation/safeNext';
import { hasRole } from '@app/session/sessionStore';
import { useSession } from '@app/session/useSession';

import { cx } from '../cx.ts';
import styles from './Layout.module.css';

export const MAIN_CONTENT_ID = 'main';

export type LayoutProps = {
  /** Number of cart lines shown in the header badge (the cart feature wires the real count). */
  readonly cartCount?: number;
};

/**
 * The storefront shell: skip link, banner with navigation and session controls, the main
 * landmark where routes render, and the footer. The console link exists only for operators;
 * services still authorise (FR-012).
 */
export function Layout({ cartCount = 0 }: LayoutProps): JSX.Element {
  const { summary, endsSoon, signOut } = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);
  const signedIn = summary.state === 'signedIn';
  const operator = hasRole(summary, 'operator');

  const onSignOut = async (): Promise<void> => {
    correlation.next();
    setSigningOut(true);
    try {
      await signOut();
    } finally {
      setSigningOut(false);
      await navigate('/', { replace: true });
    }
  };

  return (
    <div className={styles.shell}>
      <a className={styles.skipLink} href={`#${MAIN_CONTENT_ID}`}>
        Skip to main content
      </a>
      <header className={styles.header}>
        <div className={styles.headerInner}>
          <Link className={styles.brand} to="/">
            Storefront
          </Link>
          <nav className={styles.nav} aria-label="Primary">
            <NavLink className={cx(styles.navLink)} to="/" end>
              Products
            </NavLink>
            <NavLink className={cx(styles.navLink)} to="/search">
              Search
            </NavLink>
            <NavLink className={cx(styles.navLink)} to="/cart">
              Cart
              <span className={styles.badge} aria-label={`${cartCount} items in cart`}>
                {cartCount}
              </span>
            </NavLink>
            {signedIn ? (
              <>
                <NavLink className={cx(styles.navLink)} to="/orders">
                  Orders
                </NavLink>
                <NavLink className={cx(styles.navLink)} to="/account">
                  Account
                </NavLink>
                {operator ? (
                  <NavLink className={cx(styles.navLink)} to="/console/orders">
                    Console
                  </NavLink>
                ) : null}
                <button
                  className={styles.textButton}
                  type="button"
                  disabled={signingOut}
                  onClick={() => {
                    void onSignOut();
                  }}
                >
                  Sign out
                </button>
              </>
            ) : (
              <Link
                className={cx(styles.navLink)}
                to={signInLocationFor(`${location.pathname}${location.search}`)}
              >
                Sign in
              </Link>
            )}
          </nav>
        </div>
        {endsSoon ? (
          <p className={styles.notice} role="status">
            Your session is about to end. Any action keeps you signed in.
          </p>
        ) : null}
      </header>
      <main id={MAIN_CONTENT_ID} className={styles.main} tabIndex={-1}>
        <Outlet />
      </main>
      <footer className={styles.footer}>
        <div className={styles.footerInner}>Local development storefront</div>
      </footer>
    </div>
  );
}
