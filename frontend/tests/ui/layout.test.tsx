import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { renderApp } from './render.tsx';

describe('Layout', () => {
  it('exposes the landmarks, the skip link and the cart badge', async () => {
    renderApp('/');
    expect(await screen.findByRole('heading', { level: 1, name: 'Products' })).toBeInTheDocument();
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    const skip = screen.getByRole('link', { name: 'Skip to main content' });
    expect(skip).toHaveAttribute('href', '#main');
    const cart = screen.getByRole('link', { name: /Cart/ });
    expect(within(cart).getByLabelText('0 items in cart')).toHaveTextContent('0');
  });

  it('offers Sign in to an anonymous visitor, with the current page as the return target', async () => {
    renderApp('/cart');
    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute(
        'href',
        '/sign-in?next=%2Fcart',
      );
    });
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Console' })).not.toBeInTheDocument();
  });

  it('offers Sign out and account links to a shopper, but no console link', async () => {
    renderApp('/', { kind: 'signedIn', roles: ['shopper'] });
    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Orders' })).toHaveAttribute('href', '/orders');
    expect(screen.getByRole('link', { name: 'Account' })).toHaveAttribute('href', '/account');
    expect(screen.queryByRole('link', { name: 'Console' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('shows the console link only when the roles contain operator', async () => {
    renderApp('/', { kind: 'signedIn', roles: ['shopper', 'operator'] });
    expect(await screen.findByRole('link', { name: 'Console' })).toHaveAttribute(
      'href',
      '/console/orders',
    );
  });

  it('signs out through the store and returns to the home page', async () => {
    const user = userEvent.setup();
    const { harness } = renderApp('/account', { kind: 'signedIn', roles: ['shopper'] });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your account' }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(harness.port.calls).toContain('signOut');
    expect(screen.getByRole('heading', { level: 1, name: 'Products' })).toBeInTheDocument();
    expect(harness.sessionStore.current().state).toBe('anonymous');
  });

  it('puts the skip link first in the keyboard focus order, then the brand and the navigation', async () => {
    const user = userEvent.setup();
    renderApp('/');
    await screen.findByRole('heading', { level: 1, name: 'Products' });
    await user.tab();
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Storefront' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Products' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Search' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: /Cart/ })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveFocus();
  });
});
