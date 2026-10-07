import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ROUTE_TEMPLATES } from '@domain/routeTemplate';
import { ROUTE_ACCESS } from '@ui/routes/router';

import { renderApp } from './render.tsx';
import { gardenTools, rake } from '../msw/catalog.ts';

const ID = '0b4e6d1c-7e1c-4a7b-9c43-2d5e8f1a2b3c';
// Catalogue routes resolve their id against the MSW fixtures; every other id is opaque.
const instantiate = (template: string) =>
  template
    .replace('/categories/:id', `/categories/${gardenTools.id}`)
    .replace('/products/:id', `/products/${rake.id}`)
    .replace(':id', ID);

describe('router (contracts/storefront-routes.md)', () => {
  it('registers every route of the closed list with an access level', () => {
    expect(Object.keys(ROUTE_ACCESS).sort()).toEqual([...ROUTE_TEMPLATES].sort());
  });

  it('renders the not-found page inside the shell for unknown paths', async () => {
    renderApp('/does/not/exist');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('banner')).toBeInTheDocument();
  });

  it('resolves every anonymous route without a session', async () => {
    for (const template of ROUTE_TEMPLATES.filter((t) => ROUTE_ACCESS[t] === 'anonymous')) {
      const { unmount } = renderApp(instantiate(template));
      expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: 'Page not found' })).not.toBeInTheDocument();
      unmount();
    }
  });

  it('redirects an anonymous visitor of a protected route to /sign-in?next=<path> with replace', async () => {
    for (const template of ROUTE_TEMPLATES.filter((t) => ROUTE_ACCESS[t] !== 'anonymous')) {
      const path = `${instantiate(template)}?page=1`;
      const { router, unmount } = renderApp(path);
      await waitFor(() => {
        expect(router.state.location.pathname).toBe('/sign-in');
      });
      expect(router.state.location.search).toBe(`?next=${encodeURIComponent(path)}`);
      expect(router.state.historyAction).toBe('REPLACE');
      expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
      unmount();
    }
  });

  it('lets a signed-in shopper open shopper routes and shows "not allowed" on the console', async () => {
    const { unmount } = renderApp('/orders', { kind: 'signedIn', roles: ['shopper'] });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your orders' }),
    ).toBeInTheDocument();
    unmount();
    const { router } = renderApp('/console/orders', { kind: 'signedIn', roles: ['shopper'] });
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Not allowed' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/console/orders');
  });

  it('lets an operator open the console', async () => {
    renderApp('/console/stock', { kind: 'signedIn', roles: ['shopper', 'operator'] });
    expect(await screen.findByRole('heading', { level: 1, name: 'Stock' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Console' })).toBeInTheDocument();
  });

  it('a 401 while signed in sends the visitor back to anonymous navigation', async () => {
    const { harness } = renderApp('/', { kind: 'signedIn', roles: ['shopper'] });
    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    harness.port.emitUnauthorized();
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });
});
