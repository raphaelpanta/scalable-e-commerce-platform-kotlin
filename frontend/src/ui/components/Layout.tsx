import { type JSX, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router';

import { useCart } from '@app/cart/useCart';
import { clearDraft } from '@app/checkout/draftStorage';
import { correlation } from '@app/correlation';
import { signInLocationFor } from '@app/navigation/safeNext';
import { hasRole } from '@app/session/sessionStore';
import { useSession } from '@app/session/useSession';

import { BRAND_TAGLINE } from '../brand/brand.ts';
import { Wordmark } from '../brand/Wordmark.tsx';
import { cx } from '../cx.ts';
import styles from './Layout.module.css';

export const MAIN_CONTENT_ID = 'main';

/**
 * The storefront shell: skip link, banner with navigation (the cart badge counts the units of the
 * cart the platform reports) and session controls, the merge notice shown once after sign-in, the
 * main landmark where routes render, and the footer. The console link exists only for operators;
 * services still authorise (FR-012).
 */
export function Layout(): JSX.Element {
  const { summary, endsSoon, signOut } = useSession();
  const { view: cart, actions: cartActions } = useCart();
  const cartCount = cart.itemCount;
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
      // Nothing of the account stays in the tab: the queries are dropped by the session store,
      // the checkout draft (an address id and a payment method) here.
      clearDraft(window.sessionStorage);
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
            <Wordmark />
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
        {cart.mergeNotice === undefined ? null : (
          <div className={styles.notice} role="status">
            <p>
              Your cart was merged with the items you had before signing in. Some quantities were
              capped at the available stock:
            </p>
            <ul className={styles.noticeList}>
              {cart.mergeNotice.map((capped) => (
                <li key={capped.productId}>
                  {cart.lines.find((line) => line.productId === capped.productId)?.productName ??
                    'An item'}
                  : requested {capped.requestedQuantity}, kept {capped.appliedQuantity}
                </li>
              ))}
            </ul>
            <button
              className={styles.textButton}
              type="button"
              onClick={() => {
                cartActions.dismissMergeNotice();
              }}
            >
              Dismiss
            </button>
          </div>
        )}
      </header>
      <main id={MAIN_CONTENT_ID} className={styles.main} tabIndex={-1}>
        <Outlet />
      </main>
      <footer className={styles.footer}>
        <div className={styles.footerInner}>
          <Wordmark />
          <p className={styles.tagline}>{BRAND_TAGLINE}</p>
          <p className={styles.copyright}>© Vibestore. Local development storefront.</p>
        </div>
      </footer>
    </div>
  );
}
