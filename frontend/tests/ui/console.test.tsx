import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { renderApp } from './render.tsx';
import { rake, soldOutLamp } from '../msw/catalog.ts';
import { consoleOrder, consoleServer, seedOrders } from '../msw/console.ts';
import { orderServer } from '../msw/order.ts';
import { paymentServer } from '../msw/payment.ts';
import { server } from '../msw/server.ts';

// The operator console (US6, FR-011, FR-012): the list of every order with the platform's
// `orderStatus` filter, the order page with only the transitions the platform allows and a
// confirmation before each, the stock page with the platform's 422 next to its field, and the
// absence of any catalogue editing. MSW stands in for the platform (tests/msw/console.ts).
const OPERATOR = { kind: 'signedIn', roles: ['shopper', 'operator'] } as const;
const SHOPPER = { kind: 'signedIn', roles: ['shopper'] } as const;

const PLACED = '11111111-1111-4111-8111-111111111111';
const PENDING = '22222222-2222-4222-8222-222222222222';
const SHIPPED = '33333333-3333-4333-8333-333333333333';
const CANCELLED = '44444444-4444-4444-8444-444444444444';
const PREPARING = '55555555-5555-4555-8555-555555555555';
const DELIVERED = '66666666-6666-4666-8666-666666666666';

function arm(): void {
  consoleServer.serveList = true;
  seedOrders(
    consoleOrder(PLACED, 'placed', 'approved', '2026-10-02T10:15:00Z'),
    consoleOrder(PENDING, 'placed', 'pending', '2026-10-02T10:10:00Z'),
    consoleOrder(SHIPPED, 'shipped', 'approved', '2026-10-02T10:05:00Z'),
    consoleOrder(CANCELLED, 'cancelled', 'approved', '2026-10-02T10:00:00Z'),
  );
}

function rowOf(table: HTMLElement, number: string): HTMLElement {
  return within(table)
    .getAllByRole('row')
    .find((row) => within(row).queryByRole('link', { name: number }) !== null)!;
}

async function openConsoleOrder(id: string) {
  const view = renderApp(`/console/orders/${id}`, OPERATOR);
  await screen.findByRole('heading', { level: 1, name: `Order ${id.slice(0, 8)}` });
  return view;
}

describe('console entry points (FR-012)', () => {
  it('shows the Console link to an operator and a console navigation with its two pages', async () => {
    arm();
    renderApp('/console/orders', OPERATOR);
    await screen.findByRole('heading', { level: 1, name: 'Orders' });
    expect(
      within(screen.getByRole('navigation', { name: 'Primary' })).getByRole('link', {
        name: 'Console',
      }),
    ).toHaveAttribute('href', '/console/orders');
    const console = screen.getByRole('navigation', { name: 'Console' });
    expect(within(console).getByRole('link', { name: 'Orders' })).toHaveAttribute(
      'href',
      '/console/orders',
    );
    expect(within(console).getByRole('link', { name: 'Stock' })).toHaveAttribute(
      'href',
      '/console/stock',
    );
  });

  it('never shows the Console link to a shopper', async () => {
    renderApp('/', SHOPPER);
    await screen.findByRole('button', { name: 'Sign out' });
    expect(screen.queryByRole('link', { name: 'Console' })).not.toBeInTheDocument();
  });

  it('shows a signed-in shopper the not-allowed state and requests nothing of the console', async () => {
    arm();
    renderApp('/console/orders', SHOPPER);
    expect(await screen.findByRole('heading', { level: 1, name: 'Not allowed' })).toBeVisible();
    expect(screen.getByText('The console is available to operators only.')).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Console' })).not.toBeInTheDocument();
    expect(consoleServer.listings).toHaveLength(0);
    for (const path of ['/console/orders/' + PLACED, '/console/stock']) {
      const view = renderApp(path, SHOPPER);
      expect(await screen.findByRole('heading', { level: 1, name: 'Not allowed' })).toBeVisible();
      view.unmount();
    }
    expect(consoleServer.productRequests).toHaveLength(0);
    expect(orderServer.reads).toHaveLength(0);
  });

  it("surfaces the platform's 403 without data when it refuses a session that claims the role", async () => {
    arm();
    consoleServer.forbidAll = true;
    renderApp('/console/orders', OPERATOR);
    expect(await screen.findByRole('heading', { level: 1, name: 'Not allowed' })).toBeVisible();
    expect(screen.getByText(/This operation requires the operator role\./)).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByText('Ana Silva')).not.toBeInTheDocument();
    expect(consoleServer.listings).toHaveLength(1);
  });

  it('sends an anonymous visitor to sign in and back', async () => {
    arm();
    renderApp('/console/stock');
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible();
  });
});

describe('/console/orders', () => {
  it('lists every order with both statuses, newest first, each linking to its page', async () => {
    arm();
    renderApp('/console/orders', OPERATOR);
    const table = await screen.findByRole('table', { name: 'Orders' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((row) => within(row).getByRole('link').textContent)).toEqual([
      '11111111',
      '22222222',
      '33333333',
      '44444444',
    ]);
    expect(within(rowOf(table, '11111111')).getByText('Placed')).toBeVisible();
    expect(within(rowOf(table, '11111111')).getByText('Approved')).toBeVisible();
    expect(within(rowOf(table, '22222222')).getByText('Awaiting payment')).toBeVisible();
    expect(within(rowOf(table, '33333333')).getByText('Shipped')).toBeVisible();
    expect(within(rowOf(table, '44444444')).getByText('Cancelled')).toBeVisible();
    expect(within(rowOf(table, '11111111')).getByRole('link')).toHaveAttribute(
      'href',
      `/console/orders/${PLACED}`,
    );
    expect(consoleServer.listings.every((query) => !query.has('orderStatus'))).toBe(true);
  });

  it('filters by status through the platform and keeps the choice in the address', async () => {
    arm();
    const user = userEvent.setup();
    const { router } = renderApp('/console/orders', OPERATOR);
    await screen.findByRole('table', { name: 'Orders' });
    await user.selectOptions(screen.getByLabelText('Filter by status'), 'Shipped');
    await waitFor(() => {
      expect(router.state.location.search).toBe('?status=shipped');
    });
    const table = await screen.findByRole('table', { name: 'Orders' });
    await waitFor(() => {
      expect(within(table).getAllByRole('row')).toHaveLength(2);
    });
    expect(within(table).getByRole('link', { name: '33333333' })).toBeVisible();
    expect(consoleServer.listings.at(-1)?.get('orderStatus')).toBe('shipped');

    await user.selectOptions(screen.getByLabelText('Filter by status'), 'All statuses');
    await waitFor(() => {
      expect(router.state.location.search).toBe('');
    });
    await waitFor(() => {
      expect(
        within(screen.getByRole('table', { name: 'Orders' })).getAllByRole('row'),
      ).toHaveLength(5);
    });
  });

  it('opens on the status named in the address', async () => {
    arm();
    renderApp('/console/orders?status=cancelled', OPERATOR);
    const table = await screen.findByRole('table', { name: 'Orders' });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(screen.getByLabelText('Filter by status')).toHaveValue('cancelled');
    expect(consoleServer.listings.at(-1)?.get('orderStatus')).toBe('cancelled');
  });

  it('explains an empty result and offers all orders again', async () => {
    arm();
    const user = userEvent.setup();
    renderApp('/console/orders?status=delivered', OPERATOR);
    expect(await screen.findByRole('heading', { name: 'No orders' })).toBeVisible();
    await user.click(screen.getByRole('link', { name: 'Show all orders' }));
    expect(await screen.findByRole('table', { name: 'Orders' })).toBeVisible();
  });

  it('pages through the orders with page and size in the address', async () => {
    arm();
    const user = userEvent.setup();
    const { router } = renderApp('/console/orders?size=2', OPERATOR);
    await screen.findByRole('table', { name: 'Orders' });
    expect(screen.getByText('Page 1 of 2')).toBeVisible();
    expect(consoleServer.listings.at(-1)?.get('size')).toBe('2');
    await user.click(screen.getByRole('link', { name: 'Next page' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?size=2&page=1');
    });
    await waitFor(() => {
      const table = screen.getByRole('table', { name: 'Orders' });
      expect(within(table).getByRole('link', { name: '33333333' })).toBeVisible();
      expect(within(table).queryByRole('link', { name: '11111111' })).not.toBeInTheDocument();
    });
    expect(consoleServer.listings.at(-1)?.get('page')).toBe('1');
  });

  it('shows a readable error with retry when the platform fails', async () => {
    arm();
    const user = userEvent.setup();
    server.use(
      http.get(
        'http://localhost/api/v1/orders',
        () =>
          HttpResponse.json(
            {
              type: 'https://ecommerce.example/problems/validation',
              title: 'Validation failed',
              status: 400,
              detail: 'The orders could not be read.',
            },
            { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
          ),
        { once: true },
      ),
    );
    renderApp('/console/orders', OPERATOR);
    expect(await screen.findByRole('alert')).toHaveTextContent('The orders could not be read.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('table', { name: 'Orders' })).toBeVisible();
  });
});

describe('/console/orders/:id', () => {
  it('shows lines, amounts, address and the history without an account id', async () => {
    arm();
    paymentServer.attempts = [];
    await openConsoleOrder(SHIPPED);
    expect(screen.getByText('Ana Silva', { exact: false })).toBeVisible();
    expect(screen.getByRole('table', { name: 'Items' })).toBeVisible();
    const history = screen.getByRole('table', { name: 'Status history' });
    expect(within(history).getAllByText('Operator').length).toBeGreaterThan(0);
    expect(within(history).getAllByText('Shopper')).toHaveLength(1);
    expect(within(history).getAllByText('System').length).toBeGreaterThan(0);
    expect(document.body.textContent).not.toContain('e5a1c3b7-2d40');
    expect(document.body.textContent).not.toContain('7c1d4f3e-0a52');
  });

  it('offers only the transitions the platform allows for the statuses of the order', async () => {
    arm();
    const names = async (id: string): Promise<string[]> => {
      const view = await openConsoleOrder(id);
      const group = screen.queryByRole('group', { name: 'Order actions' });
      const labels =
        group === null
          ? []
          : within(group)
              .getAllByRole('button')
              .map((button) => button.textContent);
      view.unmount();
      return labels;
    };
    expect(await names(PLACED)).toEqual(['Start preparing', 'Cancel order']);
    expect(await names(SHIPPED)).toEqual(['Mark as delivered']);
    expect(await names(CANCELLED)).toEqual([]);
    seedOrders(
      consoleOrder(PREPARING, 'preparing', 'approved'),
      consoleOrder(DELIVERED, 'delivered', 'approved'),
    );
    expect(await names(PREPARING)).toEqual(['Mark as shipped', 'Cancel order']);
    expect(await names(DELIVERED)).toEqual([]);
  });

  it('does not offer preparing while the payment is pending, and says why', async () => {
    arm();
    await openConsoleOrder(PENDING);
    const group = screen.getByRole('group', { name: 'Order actions' });
    expect(
      within(group)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Cancel order']);
    expect(screen.getByText(/can be prepared once its payment is approved/)).toBeVisible();
  });

  it('asks for confirmation, then moves the order and shows the new status', async () => {
    arm();
    seedOrders(consoleOrder(PREPARING, 'preparing', 'approved'));
    const user = userEvent.setup();
    await openConsoleOrder(PREPARING);
    await user.click(screen.getByRole('button', { name: 'Mark as shipped' }));
    const dialog = await screen.findByRole('dialog', { name: 'Mark order 55555555 as shipped?' });
    expect(consoleServer.transitions).toHaveLength(0);
    await user.click(within(dialog).getByRole('button', { name: 'Mark as shipped' }));
    expect(await screen.findByText('Order 55555555 is now Shipped.')).toBeVisible();
    expect(consoleServer.transitions).toEqual([{ orderId: PREPARING, orderStatus: 'shipped' }]);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    const statuses = screen.getByText('Order status', { exact: true }).nextElementSibling;
    expect(statuses).toHaveTextContent('Shipped');
    const group = screen.getByRole('group', { name: 'Order actions' });
    expect(
      within(group)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual(['Mark as delivered']);
  });

  it('sends nothing when the confirmation is declined', async () => {
    arm();
    const user = userEvent.setup();
    await openConsoleOrder(PLACED);
    await user.click(screen.getByRole('button', { name: 'Cancel order' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel order 11111111?' });
    await user.click(within(dialog).getByRole('button', { name: 'Keep the order' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(consoleServer.transitions).toHaveLength(0);
  });

  it('cancels a placed order after confirmation and shows the cancellation', async () => {
    arm();
    const user = userEvent.setup();
    await openConsoleOrder(PLACED);
    await user.click(screen.getByRole('button', { name: 'Cancel order' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cancel order 11111111?' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel the order' }));
    expect(await screen.findByText('Order 11111111 is now Cancelled.')).toBeVisible();
    expect(consoleServer.transitions).toEqual([{ orderId: PLACED, orderStatus: 'cancelled' }]);
    expect(screen.getByText('Order status', { exact: true }).nextElementSibling).toHaveTextContent(
      'Cancelled (cancelled by the store)',
    );
    expect(screen.queryByRole('group', { name: 'Order actions' })).not.toBeInTheDocument();
  });

  it('explains a refused transition and leaves the displayed status as it was', async () => {
    arm();
    const user = userEvent.setup();
    await openConsoleOrder(PLACED);
    consoleServer.refuseNextTransition = 'Cannot move an order from placed to preparing.';
    await user.click(screen.getByRole('button', { name: 'Start preparing' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Start preparing' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Cannot move an order from placed to preparing.',
    );
    expect(screen.getByText('Order status', { exact: true }).nextElementSibling).toHaveTextContent(
      'Placed',
    );
    expect(orderServer.orders.get(PLACED)?.orderStatus).toBe('placed');
    expect(screen.getByRole('button', { name: 'Start preparing' })).toBeEnabled();
  });

  it('shows the platform refusal of a caller without the role, without changing anything', async () => {
    arm();
    const user = userEvent.setup();
    await openConsoleOrder(PLACED);
    consoleServer.forbidAll = true;
    await user.click(screen.getByRole('button', { name: 'Start preparing' }));
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Start preparing' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The platform refused this action: only operators can change an order.',
    );
    expect(screen.getByText('Order status', { exact: true }).nextElementSibling).toHaveTextContent(
      'Placed',
    );
  });

  it('disables the actions while a change is in flight', async () => {
    arm();
    const user = userEvent.setup();
    await openConsoleOrder(PLACED);
    server.use(
      http.post(
        'http://localhost/api/v1/orders/:orderId/status',
        () => new Promise(() => undefined),
      ),
    );
    await user.click(screen.getByRole('button', { name: 'Start preparing' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Start preparing' }));
    await waitFor(() => {
      expect(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Start preparing' }),
      ).toBeDisabled();
    });
  });

  it('lists the payment attempts of the order', async () => {
    arm();
    paymentServer.attempts = [
      {
        id: '8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e',
        orderId: PLACED,
        amount: { amountMinor: 2000, currency: 'BRL' },
        outcome: 'declined',
        declineReason: 'card_rejected',
        providerReference: 'sim_ch_000124',
        idempotencyKey: '2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81',
        createdAt: '2026-10-02T10:30:00Z',
      },
    ];
    await openConsoleOrder(PLACED);
    const attempts = await screen.findByRole('table', { name: 'Payment attempts' });
    expect(within(attempts).getByText('Declined')).toBeVisible();
    expect(within(attempts).getByText('the card was rejected')).toBeVisible();
  });

  it('shows the not-found page for an order the platform does not know', async () => {
    arm();
    const unknown = renderApp('/console/orders/77777777-7777-4777-8777-777777777777', OPERATOR);
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
    unknown.unmount();
    renderApp('/console/orders/not-an-order', OPERATOR);
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
  });
});

describe('/console/stock', () => {
  async function openStock(path = '/console/stock') {
    const view = renderApp(path, OPERATOR);
    await screen.findByRole('heading', { level: 1, name: 'Stock' });
    return view;
  }

  it('lists products including the withdrawn ones, with the units on hand', async () => {
    await openStock();
    const table = await screen.findByRole('table', { name: 'Products' });
    expect(consoleServer.productRequests[0]?.get('includeWithdrawn')).toBe('true');
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(3);
    const kettle = within(table).getByRole('row', { name: /Withdrawn kettle/ });
    expect(within(kettle).getByText('Withdrawn')).toBeVisible();
    const rakeRow = within(table).getByRole('row', { name: /Rake/ });
    expect(within(rakeRow).getByText('Active')).toBeVisible();
    expect(within(rakeRow).getByRole('cell', { name: '10' })).toBeVisible();
    expect(
      within(within(table).getByRole('row', { name: /Sold-out lamp/ })).getByRole('cell', {
        name: '0',
      }),
    ).toBeVisible();
  });

  it('searches products by name and keeps the term in the address', async () => {
    const user = userEvent.setup();
    const { router } = await openStock();
    await screen.findByRole('table', { name: 'Products' });
    await user.type(screen.getByLabelText('Search products'), 'rake');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?q=rake');
    });
    await waitFor(() => {
      expect(consoleServer.productRequests.at(-1)?.get('q')).toBe('rake');
    });
    await waitFor(() => {
      expect(
        within(screen.getByRole('table', { name: 'Products' })).getAllByRole('row'),
      ).toHaveLength(2);
    });
    expect(consoleServer.productRequests.at(-1)?.get('includeWithdrawn')).toBe('true');
  });

  it('adjusts stock for a reason and shows the new quantity', async () => {
    const user = userEvent.setup();
    await openStock();
    await screen.findByRole('table', { name: 'Products' });
    await user.click(screen.getByRole('button', { name: 'Adjust stock of Sold-out lamp' }));
    const form = await screen.findByRole('form', { name: 'Adjust stock of Sold-out lamp' });
    expect(within(form).getByLabelText('Change in units')).toHaveFocus();
    await user.type(within(form).getByLabelText('Change in units'), '+5');
    await user.type(within(form).getByLabelText('Reason'), '  Restock from the supplier ');
    await user.click(within(form).getByRole('button', { name: 'Apply adjustment' }));
    expect(await screen.findByText('Stock of Sold-out lamp is now 5 (was 0).')).toBeVisible();
    expect(consoleServer.adjustments).toEqual([
      { productId: soldOutLamp.id, delta: 5, reason: 'Restock from the supplier' },
    ]);
    await waitFor(() => {
      expect(
        within(
          within(screen.getByRole('table', { name: 'Products' })).getByRole('row', {
            name: /Sold-out lamp/,
          }),
        ).getByRole('cell', { name: '5' }),
      ).toBeVisible();
    });
  });

  it("shows the platform's 422 next to the delta field", async () => {
    const user = userEvent.setup();
    await openStock();
    await screen.findByRole('table', { name: 'Products' });
    await user.click(screen.getByRole('button', { name: 'Adjust stock of Rake' }));
    const form = await screen.findByRole('form', { name: 'Adjust stock of Rake' });
    await user.type(within(form).getByLabelText('Change in units'), '-99');
    await user.type(within(form).getByLabelText('Reason'), 'Recount');
    await user.click(within(form).getByRole('button', { name: 'Apply adjustment' }));
    const delta = within(form).getByLabelText('Change in units');
    await waitFor(() => {
      expect(delta).toHaveAccessibleDescription(/would make available quantity negative/);
    });
    expect(delta).toBeInvalid();
    expect(within(form).getByLabelText('Reason')).not.toBeInvalid();
    expect(consoleServer.stock.get(rake.id)).toBe(10);
  });

  it("shows the platform's 422 next to the reason field", async () => {
    server.use(
      http.post('http://localhost/api/v1/catalog/products/:productId/stock-adjustments', () =>
        HttpResponse.json(
          {
            type: 'https://ecommerce.example/problems/validation',
            title: 'Validation failed',
            status: 422,
            errors: [{ field: 'reason', message: 'must be at most 200 characters' }],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    await openStock();
    await user.click(await screen.findByRole('button', { name: 'Adjust stock of Rake' }));
    await user.type(screen.getByLabelText('Change in units'), '3');
    await user.type(screen.getByLabelText('Reason'), 'Recount');
    await user.click(screen.getByRole('button', { name: 'Apply adjustment' }));
    await waitFor(() => {
      expect(screen.getByLabelText('Reason')).toHaveAccessibleDescription(
        /must be at most 200 characters/,
      );
    });
  });

  it('checks the delta and the reason before sending anything', async () => {
    const user = userEvent.setup();
    await openStock();
    await user.click(await screen.findByRole('button', { name: 'Adjust stock of Rake' }));
    await user.type(screen.getByLabelText('Change in units'), '0');
    await user.click(screen.getByRole('button', { name: 'Apply adjustment' }));
    expect(screen.getByLabelText('Change in units')).toHaveAccessibleDescription(
      'Enter a whole number other than zero.',
    );
    expect(screen.getByLabelText('Reason')).toHaveAccessibleDescription('Give a reason.');
    await user.clear(screen.getByLabelText('Change in units'));
    await user.type(screen.getByLabelText('Change in units'), '1.5');
    await user.type(screen.getByLabelText('Reason'), 'x'.repeat(256));
    await user.click(screen.getByRole('button', { name: 'Apply adjustment' }));
    expect(screen.getByLabelText('Change in units')).toHaveAccessibleDescription(
      'Enter a whole number, for example 5 or -2.',
    );
    expect(screen.getByLabelText('Reason')).toHaveAccessibleDescription(
      'Use at most 255 characters.',
    );
    expect(consoleServer.adjustments).toHaveLength(0);
  });

  it('keeps the reason out of the address and out of browser storage', async () => {
    const user = userEvent.setup();
    const { router } = await openStock();
    await user.click(await screen.findByRole('button', { name: 'Adjust stock of Rake' }));
    await user.type(screen.getByLabelText('Change in units'), '2');
    await user.type(screen.getByLabelText('Reason'), 'private-reason-text');
    await user.click(screen.getByRole('button', { name: 'Apply adjustment' }));
    await screen.findByText(/Stock of Rake is now 12/);
    expect(router.state.location.search).not.toContain('private-reason-text');
    for (let index = 0; index < window.sessionStorage.length; index += 1) {
      const key = window.sessionStorage.key(index) ?? '';
      expect(key + (window.sessionStorage.getItem(key) ?? '')).not.toContain('private-reason-text');
    }
  });

  it('offers no way to create, edit, withdraw or reinstate products and says so', async () => {
    await openStock();
    await screen.findByRole('table', { name: 'Products' });
    const forbidden = /^(create|new|add|edit|withdraw|reinstate|delete)\b/i;
    expect(screen.queryAllByRole('button', { name: forbidden })).toHaveLength(0);
    expect(screen.queryAllByRole('link', { name: forbidden })).toHaveLength(0);
    expect(screen.getByText(/catalogue editing/i)).toHaveTextContent(
      /done through the API for now/,
    );
  });

  it('pages through the products with page and size in the address', async () => {
    const user = userEvent.setup();
    const { router } = await openStock('/console/stock?size=2');
    await screen.findByRole('table', { name: 'Products' });
    expect(screen.getByText('Page 1 of 2')).toBeVisible();
    await user.click(screen.getByRole('link', { name: 'Next page' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?size=2&page=1');
    });
  });
});
