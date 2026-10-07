import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { UNAVAILABLE_KEY } from '@app/cart/cartStore';

import { renderApp } from './render.tsx';
import { cartServer } from '../msw/cart.ts';
import { rake, soldOutLamp, spade } from '../msw/catalog.ts';

function line(name: string): HTMLElement {
  return within(screen.getByRole('list', { name: 'Cart lines' })).getByRole('listitem', { name });
}

/** The cart page with its lines loaded. */
async function openCart(path = '/cart'): Promise<ReturnType<typeof renderApp>> {
  const rendered = renderApp(path);
  await screen.findByRole('heading', { level: 1, name: 'Your cart' });
  await screen.findByRole('list', { name: 'Cart lines' });
  return rendered;
}

describe('Add to cart from the product page (FR-004)', () => {
  it('adds the chosen quantity, reports it, counts it in the header badge and shows the line', async () => {
    const user = userEvent.setup();
    renderApp(`/products/${rake.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Rake' });
    const quantity = screen.getByLabelText('Quantity');
    await user.clear(quantity);
    await user.type(quantity, '2');
    await user.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Added 2 to your cart.');
    await waitFor(() => {
      expect(screen.getByLabelText('2 items in cart')).toHaveTextContent('2');
    });
    const added = cartServer.requests.find((request) => request.method === 'POST');
    expect(added?.body).toEqual({ productId: rake.id, quantity: 2 });

    await user.click(screen.getByRole('link', { name: 'View cart' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Your cart' })).toBeInTheDocument();
    await screen.findByRole('list', { name: 'Cart lines' });
    const rakeLine = line('Rake');
    expect(within(rakeLine).getByLabelText('Quantity for Rake')).toHaveValue(2);
    expect(within(rakeLine).getByText('Line total')).toHaveTextContent('R$20.00');
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$20.00');
  });

  it('shows the platform refusal when the quantity exceeds the stock, and keeps the cart as it was', async () => {
    const user = userEvent.setup();
    renderApp(`/products/${rake.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Rake' });
    const quantity = screen.getByLabelText('Quantity');
    await user.clear(quantity);
    await user.type(quantity, '9');
    await user.click(screen.getByRole('button', { name: 'Add to cart' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Only 5 units are available; requested 9.',
    );
    expect(screen.getByLabelText('0 items in cart')).toHaveTextContent('0');
  });

  it('keeps "Add to cart" disabled with its explanation when the product is out of stock', async () => {
    renderApp(`/products/${soldOutLamp.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Sold-out lamp' });
    expect(screen.getByRole('button', { name: 'Add to cart' })).toBeDisabled();
    expect(screen.getByLabelText('Quantity')).toBeDisabled();
  });
});

describe('Cart page (FR-004, storefront-routes.md /cart)', () => {
  it('changes a quantity and removes lines, every total coming from the server, down to the empty state', async () => {
    const user = userEvent.setup();
    cartServer.seed([
      { productId: rake.id, quantity: 2 },
      { productId: spade.id, quantity: 1 },
    ]);
    await openCart();
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$40.00');
    expect(screen.getByLabelText('3 items in cart')).toBeInTheDocument();

    const rakeLine = line('Rake');
    const quantity = within(rakeLine).getByLabelText('Quantity for Rake');
    await user.clear(quantity);
    await user.type(quantity, '3');
    await user.click(within(rakeLine).getByRole('button', { name: 'Update' }));
    await waitFor(() => {
      expect(within(line('Rake')).getByText('Line total')).toHaveTextContent('R$30.00');
    });
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$50.00');
    const update = cartServer.requests.find((request) => request.method === 'PUT');
    expect(update?.body).toEqual({ quantity: 3 });

    await user.click(within(line('Spade')).getByRole('button', { name: 'Remove Spade' }));
    await waitFor(() => {
      expect(screen.queryByRole('listitem', { name: 'Spade' })).not.toBeInTheDocument();
    });
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$30.00');
    // A removal is the contract's quantity 0.
    expect(cartServer.requests.filter((r) => r.method === 'PUT').at(-1)?.body).toEqual({
      quantity: 0,
    });

    await user.click(within(line('Rake')).getByRole('button', { name: 'Remove Rake' }));
    expect(await screen.findByRole('heading', { name: 'Your cart is empty' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/');
    expect(screen.getByLabelText('0 items in cart')).toBeInTheDocument();
  });

  it('shows both prices on a line whose price changed since it was added', async () => {
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    cartServer.changePrice(rake.id, 1200);
    await openCart();
    const rakeLine = line('Rake');
    const notice = within(rakeLine).getByText(/Price changed since you added it/);
    expect(notice).toHaveTextContent('was R$10.00, now R$12.00.');
    expect(within(rakeLine).getByText('Unit price')).toHaveTextContent('R$12.00');
  });

  it('rejects an invalid quantity locally and rolls an optimistic quantity back when the platform refuses it', async () => {
    const user = userEvent.setup();
    cartServer.seed([{ productId: rake.id, quantity: 2 }]);
    await openCart();
    const quantity = within(line('Rake')).getByLabelText('Quantity for Rake');

    await user.clear(quantity);
    await user.type(quantity, '0');
    await user.click(within(line('Rake')).getByRole('button', { name: 'Update' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Enter a whole number between 1 and 99.',
    );
    expect(cartServer.requests.filter((r) => r.method === 'PUT')).toHaveLength(0);

    await user.clear(quantity);
    await user.type(quantity, '9');
    await user.click(within(line('Rake')).getByRole('button', { name: 'Update' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Only 5 units are available; requested 9.',
      );
    });
    expect(within(line('Rake')).getByLabelText('Quantity for Rake')).toHaveValue(2);
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$20.00');
  });

  it('flags the lines a refused checkout named as unavailable and blocks checkout until they are removed', async () => {
    cartServer.seed([
      { productId: rake.id, quantity: 2 },
      { productId: spade.id, quantity: 1 },
    ]);
    const { harness } = await openCart();
    expect(screen.getByRole('link', { name: 'Check out' })).toHaveAttribute('href', '/checkout');

    act(() => {
      harness.queryClient.setQueryData<readonly string[]>(UNAVAILABLE_KEY, [rake.id]);
    });
    expect(
      await within(line('Rake')).findByText(
        'No longer available in this quantity. Remove it to check out.',
      ),
    ).toBeInTheDocument();
    expect(within(line('Spade')).queryByText(/No longer available/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Check out' })).not.toBeInTheDocument();
    expect(screen.getByText('Remove the unavailable items to check out.')).toBeInTheDocument();
  });

  it('is open to an anonymous visitor and sends them to sign in with the cart kept when they check out', async () => {
    const user = userEvent.setup();
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    const { router } = await openCart();
    await user.click(screen.getByRole('link', { name: 'Check out' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/sign-in');
    });
    expect(router.state.location.search).toBe('?next=%2Fcheckout');
    expect(cartServer.lines).toHaveLength(1);
  });
});
