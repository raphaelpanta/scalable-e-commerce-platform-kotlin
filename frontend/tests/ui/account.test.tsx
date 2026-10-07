import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';

import type { Order } from '@app/order/orderPort';
import { orderKeys } from '@app/order/useOrder';

import { renderApp } from './render.tsx';
import { PROBLEM } from '../msw/catalog.ts';
import {
  ADDRESSES_URL,
  ANA,
  GENERIC_RESET_MESSAGE,
  homeAddress,
  identityServer,
  ME_URL,
  PHONE_CODE,
  PHONE_NUMBER,
  PREFERENCES_URL,
  RESET_TOKEN,
  RESETS_URL,
  workAddress,
} from '../msw/identity.ts';
import { OPERATOR_ID, ORDERS_URL, orderServer, OWNER_ID, seedOrder } from '../msw/order.ts';
import { paymentServer } from '../msw/payment.ts';
import { server } from '../msw/server.ts';

const shopper = { kind: 'signedIn', roles: ['shopper'] } as const;

const OLDEST = '1a000000-0000-4000-8000-000000000001';
const MIDDLE = '2b000000-0000-4000-8000-000000000002';
const NEWEST = '3c000000-0000-4000-8000-000000000003';
const ATTEMPT = '8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e';

function problemResponse(
  status: number,
  slug: string,
  title: string,
  detail: string,
  headers: Record<string, string> = {},
  extra: Record<string, unknown> = {},
) {
  return HttpResponse.json(
    { type: `https://ecommerce.example/problems/${slug}`, title, status, detail, ...extra },
    { status, headers: { 'Content-Type': PROBLEM, ...headers } },
  );
}

describe('Orders list (FR-010, FR-016)', () => {
  it('lists only the own orders, newest first, each with both statuses, the total and the date', async () => {
    seedOrder({ id: OLDEST, createdAt: '2026-10-01T09:00:00Z' });
    seedOrder({
      id: MIDDLE,
      createdAt: '2026-10-02T09:00:00Z',
      orderStatus: 'shipped',
      paymentStatus: 'approved',
    });
    seedOrder({ id: NEWEST, createdAt: '2026-10-03T09:00:00Z', paymentStatus: 'pending' });
    renderApp('/orders', shopper);
    expect(await screen.findByRole('heading', { level: 1, name: 'Your orders' })).toBeVisible();
    const list = await screen.findByRole('list', { name: 'Your orders' });
    const rows = within(list).getAllByRole('listitem');
    expect(rows.map((row) => within(row).getByRole('link').textContent)).toEqual([
      'Order 3c000000',
      'Order 2b000000',
      'Order 1a000000',
    ]);
    expect(within(rows[0]!).getByRole('link')).toHaveAttribute('href', `/orders/${NEWEST}`);
    expect(rows[0]).toHaveTextContent('Order: Placed');
    expect(rows[0]).toHaveTextContent('Payment: Awaiting payment');
    expect(rows[1]).toHaveTextContent('Order: Shipped');
    expect(rows[1]).toHaveTextContent('Payment: Approved');
    expect(rows[2]).toHaveTextContent('R$20.00');
    expect(rows[2]!.querySelector('time')).toHaveAttribute('datetime', '2026-10-01T09:00:00.000Z');
    expect(screen.getByRole('navigation', { name: 'Pagination' })).toHaveTextContent(
      'Showing 3 of 3 orders',
    );
  });

  it('pages through the orders with ?page= and ?size= in the address', async () => {
    seedOrder({ id: OLDEST, createdAt: '2026-10-01T09:00:00Z' });
    seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z' });
    seedOrder({ id: NEWEST, createdAt: '2026-10-03T09:00:00Z' });
    renderApp('/orders?page=1&size=2', shopper);
    const list = await screen.findByRole('list', { name: 'Your orders' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(within(list).getByRole('link')).toHaveTextContent('Order 1a000000');
    expect(screen.getByRole('link', { name: 'Previous page' })).toHaveAttribute(
      'href',
      '/orders?size=2',
    );
  });

  it('offers a first order when there is none', async () => {
    renderApp('/orders', shopper);
    expect(await screen.findByRole('heading', { name: 'No orders yet' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('list', { name: 'Your orders' })).not.toBeInTheDocument();
  });

  it('shows a loading status, then a readable error with retry and no raw problem', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(
        ORDERS_URL,
        () =>
          problemResponse(
            400,
            'validation',
            'Bad request',
            'The orders could not be read.',
            {},
            {},
          ),
        { once: true },
      ),
    );
    seedOrder({ id: OLDEST, createdAt: '2026-10-01T09:00:00Z' });
    renderApp('/orders', shopper);
    expect(await screen.findByRole('alert')).toHaveTextContent('The orders could not be read.');
    expect(screen.queryByText(/"type"/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('link', { name: 'Order 1a000000' })).toBeVisible();
  });

  it('shows the loading status while the platform answers', async () => {
    server.use(http.get(ORDERS_URL, () => delay('infinite')));
    renderApp('/orders', shopper);
    expect(await screen.findByText('Loading your orders…')).toBeVisible();
  });

  it('counts down a 429 and keeps retry disabled until the wait is over', async () => {
    server.use(
      http.get(ORDERS_URL, () =>
        problemResponse(429, 'throttled', 'Too many requests', 'Slow down.', {
          'Retry-After': '7',
        }),
      ),
    );
    renderApp('/orders', shopper);
    expect(await screen.findByRole('heading', { name: 'Too many requests' })).toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent('Try again in 7 seconds.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled();
  });
});

describe('Order detail (FR-009, FR-010)', () => {
  it('shows the lines, the amounts, the address and the history with you, operator and system, never an account id', async () => {
    seedOrder({
      id: MIDDLE,
      createdAt: '2026-10-02T09:00:00Z',
      orderStatus: 'shipped',
      statusHistory: [
        { kind: 'order', status: 'placed', at: '2026-10-02T09:00:00Z', by: OWNER_ID },
        { kind: 'payment', status: 'pending', at: '2026-10-02T09:00:00Z', by: 'system' },
        { kind: 'payment', status: 'approved', at: '2026-10-02T09:00:01Z', by: 'system' },
        { kind: 'order', status: 'preparing', at: '2026-10-02T10:00:00Z', by: OPERATOR_ID },
        { kind: 'order', status: 'shipped', at: '2026-10-02T11:00:00Z', by: OPERATOR_ID },
      ],
    });
    renderApp(`/orders/${MIDDLE}`, shopper);
    expect(await screen.findByRole('heading', { level: 1, name: 'Order 2b000000' })).toBeVisible();
    expect(screen.getByText(MIDDLE)).toBeVisible();
    expect(screen.getByText('Order status').nextElementSibling).toHaveTextContent('Shipped');
    expect(screen.getByText('Payment status').nextElementSibling).toHaveTextContent('Approved');
    const items = screen.getByRole('table', { name: 'Items' });
    expect(within(items).getByRole('row', { name: /Rake/ })).toHaveTextContent('R$10.00');
    expect(within(items).getByRole('row', { name: /Rake/ })).toHaveTextContent('R$20.00');
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$20.00');
    expect(screen.getByText(/Delivery to/)).toHaveTextContent(
      'Delivery to Ana Silva, Rua das Flores 12, 1000-001 Lisboa, PT.',
    );
    const history = within(screen.getByRole('list', { name: 'Status history' })).getAllByRole(
      'listitem',
    );
    expect(history).toHaveLength(5);
    expect(history[0]).toHaveTextContent('Order: Placed');
    expect(history[0]).toHaveTextContent('you');
    expect(history[1]).toHaveTextContent('Payment: Awaiting payment');
    expect(history[1]).toHaveTextContent('system');
    expect(history[3]).toHaveTextContent('Order: Being prepared');
    expect(history[3]).toHaveTextContent('operator');
    expect(history[4]).toHaveTextContent('Order: Shipped');
    expect(history[0]!.querySelector('time')).toHaveAttribute(
      'datetime',
      '2026-10-02T09:00:00.000Z',
    );
    expect(document.body.textContent).not.toContain(OWNER_ID);
    expect(document.body.textContent).not.toContain(OPERATOR_ID);
    expect(screen.getByRole('link', { name: 'Back to your orders' })).toHaveAttribute(
      'href',
      '/orders',
    );
  });

  it('shows the awaiting-payment countdown from the order deadline while the payment is pending', async () => {
    seedOrder({ id: NEWEST, createdAt: new Date().toISOString(), paymentStatus: 'pending' });
    renderApp(`/orders/${NEWEST}`, shopper);
    const timer = await screen.findByRole('timer');
    expect(timer).toHaveTextContent(
      /Awaiting payment: (29|30):\d\d left before the payment window ends\./,
    );
  });

  it('explains a cancelled order with its reason and the decline category of the failed payment', async () => {
    paymentServer.attempts = [
      {
        id: ATTEMPT,
        orderId: OLDEST,
        amount: { amountMinor: 2000, currency: 'BRL' },
        outcome: 'declined',
        declineReason: 'insufficient_funds',
        idempotencyKey: '2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81',
        createdAt: '2026-10-01T09:00:01Z',
      },
    ];
    seedOrder({
      id: OLDEST,
      createdAt: '2026-10-01T09:00:00Z',
      orderStatus: 'cancelled',
      paymentStatus: 'failed',
      cancellationReason: 'PAYMENT_FAILED',
      paymentAttemptId: ATTEMPT,
    });
    renderApp(`/orders/${OLDEST}`, shopper);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The payment failed: insufficient funds. Nothing was charged.',
    );
    expect(screen.getByText('Order status').nextElementSibling).toHaveTextContent(
      'Cancelled (cancelled because the payment failed)',
    );
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });

  it('renders the not-found page for an order that is not the own (404) and for an invalid id without asking', async () => {
    renderApp(`/orders/${NEWEST}`, shopper);
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
    expect(orderServer.reads).toEqual([NEWEST]);
  });

  it('renders the not-found page for an id that is not a UUID, with no request', async () => {
    renderApp('/orders/not-an-order', shopper);
    expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeVisible();
    expect(orderServer.reads).toEqual([]);
  });
});

describe('Cancelling an order (FR-010)', () => {
  it.each(['preparing', 'shipped', 'delivered', 'cancelled'] as const)(
    'offers no cancel action once the order is %s',
    async (status) => {
      seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z', orderStatus: status });
      renderApp(`/orders/${MIDDLE}`, shopper);
      await screen.findByRole('heading', { level: 1, name: 'Order 2b000000' });
      expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
    },
  );

  it('asks for confirmation first, then cancels and shows the new status, the reason and the history', async () => {
    const user = userEvent.setup();
    seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z' });
    const { harness } = renderApp(`/orders/${MIDDLE}`, shopper);
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    const dialog = screen.getByRole('dialog', { name: 'Cancel this order?' });
    expect(dialog).toBeVisible();
    expect(orderServer.cancellations).toEqual([]);

    await user.click(within(dialog).getByRole('button', { name: 'Keep the order' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(orderServer.cancellations).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Cancel order' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Yes, cancel the order' }),
    );
    await waitFor(() => {
      expect(screen.getByText('Order status').nextElementSibling).toHaveTextContent(
        'Cancelled (cancelled at your request)',
      );
    });
    expect(screen.getByRole('status')).toHaveTextContent('The order was cancelled.');
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
    expect(orderServer.cancellations).toEqual([MIDDLE]);
    const history = within(screen.getByRole('list', { name: 'Status history' })).getAllByRole(
      'listitem',
    );
    expect(history.at(-1)).toHaveTextContent('Order: Cancelled');
    expect(history.at(-1)).toHaveTextContent('you');
    expect(harness.queryClient.getQueryData<Order>(orderKeys.order(MIDDLE))?.orderStatus).toBe(
      'cancelled',
    );
  });

  it('closes the dialog with Escape without cancelling', async () => {
    const user = userEvent.setup();
    seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z' });
    renderApp(`/orders/${MIDDLE}`, shopper);
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    expect(screen.getByRole('dialog')).toBeVisible();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(orderServer.cancellations).toEqual([]);
    expect(screen.getByRole('button', { name: 'Cancel order' })).toHaveFocus();
  });

  it('shows the 409 order-not-cancellable reason and leaves the displayed status as it was', async () => {
    const user = userEvent.setup();
    const placed = seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z' });
    renderApp(`/orders/${MIDDLE}`, shopper);
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    // The operator ships the order after the page was loaded: the platform now refuses.
    orderServer.orders.set(MIDDLE, { ...placed, orderStatus: 'shipped' });
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Yes, cancel the order' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Shoppers can only cancel an order while it is placed; this order is shipped.',
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('Order status').nextElementSibling).toHaveTextContent('Placed');
    expect(screen.queryByText('The order was cancelled.')).not.toBeInTheDocument();
  });

  it('shows an unexpected refusal as an error with retry and keeps the status', async () => {
    const user = userEvent.setup();
    seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z' });
    server.use(
      http.post(`${ORDERS_URL}/:orderId/cancellation`, () =>
        problemResponse(400, 'validation', 'Bad request', 'The cancellation was refused.'),
      ),
    );
    renderApp(`/orders/${MIDDLE}`, shopper);
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Yes, cancel the order' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('The cancellation was refused.');
    expect(screen.getByText('Order status').nextElementSibling).toHaveTextContent('Placed');
  });

  it('counts down a throttled cancellation instead of retrying', async () => {
    const user = userEvent.setup();
    seedOrder({ id: MIDDLE, createdAt: '2026-10-02T09:00:00Z' });
    server.use(
      http.post(`${ORDERS_URL}/:orderId/cancellation`, () =>
        problemResponse(429, 'throttled', 'Too many requests', 'Slow down.', {
          'Retry-After': '9',
        }),
      ),
    );
    renderApp(`/orders/${MIDDLE}`, shopper);
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Yes, cancel the order' }),
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Try again in 9 seconds.');
    expect(orderServer.cancellations).toEqual([]);
  });
});

describe('Addresses (FR-010)', () => {
  it('lists the saved addresses with the default one marked, and offers the first one when there is none', async () => {
    identityServer.addresses = [homeAddress, workAddress];
    const first = renderApp('/account/addresses', shopper);
    const list = await screen.findByRole('list', { name: 'Saved addresses' });
    const rows = within(list).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Home');
    expect(rows[0]).toHaveTextContent('Default');
    expect(rows[0]).toHaveTextContent('Ana Silva, Rua das Flores 12, 1000-001 Lisboa, PT');
    expect(rows[1]).not.toHaveTextContent('Default');
    first.unmount();

    identityServer.addresses = [];
    renderApp('/account/addresses', shopper);
    expect(await screen.findByRole('heading', { name: 'No saved addresses' })).toBeVisible();
  });

  it('adds an address, with the platform field errors next to the fields until it is valid', async () => {
    const user = userEvent.setup();
    renderApp('/account/addresses', shopper);
    await user.click(await screen.findByRole('button', { name: 'Add an address' }));
    const form = screen.getByRole('form', { name: 'New delivery address' });
    await user.click(within(form).getByRole('button', { name: 'Save address' }));
    expect(within(form).getAllByText('This field is required.')).toHaveLength(5);
    expect(identityServer.requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    await user.type(within(form).getByLabelText('Recipient name'), 'Ana Silva');
    await user.type(within(form).getByLabelText('Address line 1'), 'Rua das Flores 12');
    await user.type(within(form).getByLabelText('City'), 'Lisboa');
    await user.type(within(form).getByLabelText('Postal code'), '00000');
    await user.type(within(form).getByLabelText('Country code'), 'pt');
    await user.click(within(form).getByRole('button', { name: 'Save address' }));
    expect(await within(form).findByText('is not a valid postal code')).toBeVisible();
    expect(within(form).getByLabelText('Postal code')).toHaveAccessibleDescription(
      'is not a valid postal code',
    );

    await user.clear(within(form).getByLabelText('Postal code'));
    await user.type(within(form).getByLabelText('Postal code'), '1000-001');
    await user.click(within(form).getByRole('button', { name: 'Save address' }));
    const list = await screen.findByRole('list', { name: 'Saved addresses' });
    expect(within(list).getByText(/Rua das Flores 12, 1000-001 Lisboa, PT/)).toBeVisible();
    expect(screen.queryByRole('form', { name: 'New delivery address' })).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Address saved.');
  });

  it('edits an address from its current values and shows a platform field error next to the field', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress, workAddress];
    renderApp('/account/addresses', shopper);
    await user.click(await screen.findByRole('button', { name: 'Edit address Work' }));
    const form = screen.getByRole('form', { name: 'Edit delivery address' });
    expect(within(form).getByLabelText('Recipient name')).toHaveValue('Ana Silva');
    expect(within(form).getByLabelText('Address line 1')).toHaveValue('Avenida da Liberdade 100');
    expect(within(form).getByLabelText('Label (optional)')).toHaveValue('Work');

    await user.clear(within(form).getByLabelText('Postal code'));
    await user.type(within(form).getByLabelText('Postal code'), '00000');
    await user.click(within(form).getByRole('button', { name: 'Save address' }));
    expect(await within(form).findByText('is not a valid postal code')).toBeVisible();

    await user.clear(within(form).getByLabelText('City'));
    await user.type(within(form).getByLabelText('City'), 'Porto');
    await user.clear(within(form).getByLabelText('Postal code'));
    await user.type(within(form).getByLabelText('Postal code'), '4000-001');
    await user.click(within(form).getByRole('button', { name: 'Save address' }));
    await waitFor(() => {
      expect(screen.queryByRole('form', { name: 'Edit delivery address' })).not.toBeInTheDocument();
    });
    const rows = within(await screen.findByRole('list', { name: 'Saved addresses' })).getAllByRole(
      'listitem',
    );
    expect(rows[1]).toHaveTextContent('4000-001 Porto');
    const put = identityServer.requests.filter((r) => r.method === 'PUT').at(-1);
    expect(put?.url).toBe(`${ADDRESSES_URL}/${workAddress.id}`);
    expect(put?.body).toMatchObject({ city: 'Porto', postalCode: '4000-001', label: 'Work' });
  });

  it('removes an address only after the shopper confirms', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress, workAddress];
    renderApp('/account/addresses', shopper);
    await user.click(await screen.findByRole('button', { name: 'Delete address Work' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete this address?' });
    await user.click(within(dialog).getByRole('button', { name: 'Keep the address' }));
    expect(identityServer.addresses).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Delete address Work' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete address' }),
    );
    await waitFor(() => {
      expect(identityServer.addresses).toEqual([homeAddress]);
    });
    const list = await screen.findByRole('list', { name: 'Saved addresses' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('Address removed.');
  });

  it('shows a refused removal (404, the address is gone) as an error', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    server.use(
      http.delete(`${ADDRESSES_URL}/:addressId`, () =>
        problemResponse(404, 'not-found', 'Not found', 'Address not found.'),
      ),
    );
    renderApp('/account/addresses', shopper);
    await user.click(await screen.findByRole('button', { name: 'Delete address Home' }));
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete address' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('Address not found.');
  });
});

describe('Notification preferences (FR-010)', () => {
  it('offers sms only after a phone number is verified, and keeps one channel on', async () => {
    const user = userEvent.setup();
    renderApp('/account/notifications', shopper);
    const email = await screen.findByRole('checkbox', { name: 'Email' });
    const sms = screen.getByRole('checkbox', { name: /SMS/ });
    expect(email).toBeChecked();
    expect(sms).toBeDisabled();
    expect(sms).not.toBeChecked();
    expect(sms).toHaveAccessibleDescription('Verify a phone number below to turn this on.');
    const save = screen.getByRole('button', { name: 'Save preferences' });
    expect(save).toBeDisabled();
    await user.click(email);
    expect(screen.getByText('Keep at least one channel on.')).toBeVisible();
    expect(save).toBeDisabled();
  });

  it('verifies a phone number with the code, then lets sms be switched on and saved', async () => {
    const user = userEvent.setup();
    renderApp('/account/notifications', shopper);
    await screen.findByRole('checkbox', { name: 'Email' });

    await user.type(screen.getByLabelText('Phone number'), '912345678');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    expect(
      await screen.findByText(
        'Enter the number in international format, for example +351912345678.',
      ),
    ).toBeVisible();
    expect(identityServer.requests.filter((r) => r.url.endsWith('/phone-verifications'))).toEqual(
      [],
    );

    await user.clear(screen.getByLabelText('Phone number'));
    await user.type(screen.getByLabelText('Phone number'), '+351 912 345 678');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByText(`We sent a code to ${PHONE_NUMBER}.`)).toBeVisible();
    expect(identityServer.pendingPhone).toBe(PHONE_NUMBER);

    await user.type(screen.getByLabelText('Verification code'), '000000');
    await user.click(screen.getByRole('button', { name: 'Confirm number' }));
    const refused = await screen.findByText('is wrong or has expired');
    expect(refused).toBeVisible();
    expect(screen.getByLabelText('Verification code')).toHaveAccessibleDescription(
      'is wrong or has expired',
    );

    await user.clear(screen.getByLabelText('Verification code'));
    await user.type(screen.getByLabelText('Verification code'), PHONE_CODE);
    await user.click(screen.getByRole('button', { name: 'Confirm number' }));
    expect(await screen.findByText(`Your phone number ${PHONE_NUMBER} is verified.`)).toBeVisible();

    const sms = await screen.findByRole('checkbox', { name: /SMS/ });
    await waitFor(() => {
      expect(sms).toBeEnabled();
    });
    await user.click(sms);
    await user.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByText('Your preferences are saved.')).toBeVisible();
    expect(identityServer.preferences.channels).toEqual(['email', 'sms']);
    const put = identityServer.requests.filter((r) => r.method === 'PUT').at(-1);
    expect(put?.body).toEqual({ channels: ['email', 'sms'] });
  });

  it('shows the platform 422 when sms is refused and keeps the stored preferences', async () => {
    const user = userEvent.setup();
    // The shopper's phone verification lapsed after the page was loaded: identity refuses sms.
    server.use(
      http.get(PREFERENCES_URL, () =>
        HttpResponse.json({
          channels: ['email'],
          phoneNumber: PHONE_NUMBER,
          phoneVerified: true,
        }),
      ),
    );
    renderApp('/account/notifications', shopper);
    await user.click(await screen.findByRole('checkbox', { name: /SMS/ }));
    await user.click(screen.getByRole('button', { name: 'Save preferences' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'sms requires a verified phone number',
    );
    expect(identityServer.preferences.channels).toEqual(['email']);
    expect(screen.queryByText('Your preferences are saved.')).not.toBeInTheDocument();
  });
});

describe('Forgot password (FR-005)', () => {
  it('shows the same confirmation whether or not the email is registered', async () => {
    const user = userEvent.setup();
    renderApp('/forgot-password');
    const email = await screen.findByLabelText('Email');
    await user.type(email, ANA.email);
    await user.click(screen.getByRole('button', { name: 'Send reset link' }));
    const known = await screen.findByRole('status');
    expect(known).toHaveTextContent(GENERIC_RESET_MESSAGE);

    await user.clear(screen.getByLabelText('Email'));
    await user.type(screen.getByLabelText('Email'), 'nobody@example.com');
    await user.click(screen.getByRole('button', { name: 'Send reset link' }));
    await waitFor(() => {
      expect(
        identityServer.requests.filter((r) => r.url.endsWith('/password-resets')),
      ).toHaveLength(2);
    });
    expect(screen.getByRole('status').textContent).toBe(known.textContent);
    expect(identityServer.requests.at(-1)?.body).toEqual({ email: 'nobody@example.com' });
  });

  it('asks for a valid email without sending anything', async () => {
    const user = userEvent.setup();
    renderApp('/forgot-password');
    await user.type(await screen.findByLabelText('Email'), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Send reset link' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid email address.');
    expect(identityServer.requests).toHaveLength(0);
  });

  it('counts down when the platform throttles the request', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(RESETS_URL, () =>
        problemResponse(429, 'throttled', 'Too many requests', 'Slow down.', {
          'Retry-After': '5',
        }),
      ),
    );
    renderApp('/forgot-password');
    await user.type(await screen.findByLabelText('Email'), ANA.email);
    await user.click(screen.getByRole('button', { name: 'Send reset link' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Try again in 5 seconds.');
    expect(screen.queryByText(GENERIC_RESET_MESSAGE)).not.toBeInTheDocument();
  });
});

describe('Reset password (FR-005, /reset-password)', () => {
  it('reads the token once, removes it from the address with a history replace and sets the new password', async () => {
    const user = userEvent.setup();
    const { router } = renderApp(`/reset-password?token=${RESET_TOKEN}`);
    const field = await screen.findByLabelText('New password');
    expect(router.state.location.pathname).toBe('/reset-password');
    expect(router.state.location.search).toBe('');
    expect(router.state.historyAction).toBe('REPLACE');

    await user.type(field, 'short');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Use at least 12 characters.');
    expect(identityServer.requests).toHaveLength(0);

    await user.clear(field);
    await user.type(field, 'N3w-passphrase-2026!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(
      await screen.findByText('Your password was changed. Sign in with the new password.'),
    ).toBeVisible();
    const sent = identityServer.requests.filter((r) => r.url.endsWith('/complete'));
    expect(sent).toHaveLength(1);
    expect(sent[0]?.body).toEqual({ token: RESET_TOKEN, newPassword: 'N3w-passphrase-2026!' });
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/sign-in',
    );
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
  });

  it('shows the platform refusal next to the field and keeps the token for another try', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(
        `${RESETS_URL}/complete`,
        () =>
          problemResponse(
            422,
            'validation',
            'Validation failed',
            'Password does not meet the policy.',
            {},
            {
              errors: [{ field: 'newPassword', message: 'is too common' }],
            },
          ),
        { once: true },
      ),
    );
    renderApp(`/reset-password?token=${RESET_TOKEN}`);
    const field = await screen.findByLabelText('New password');
    await user.type(field, 'password-password');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByText('is too common')).toBeVisible();
    expect(field).toHaveAccessibleDescription(/is too common/);

    await user.clear(field);
    await user.type(field, 'N3w-passphrase-2026!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(
      await screen.findByText('Your password was changed. Sign in with the new password.'),
    ).toBeVisible();
    const sent = identityServer.requests.filter((r) => r.url.endsWith('/complete'));
    expect(sent.at(-1)?.body).toMatchObject({ token: RESET_TOKEN });
  });

  it('shows one generic message for an invalid, used or expired link', async () => {
    const user = userEvent.setup();
    renderApp('/reset-password?token=tok-expired');
    await user.type(await screen.findByLabelText('New password'), 'N3w-passphrase-2026!');
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This reset link is invalid or has expired.',
    );
    expect(screen.getByRole('link', { name: 'Ask for a new link' })).toHaveAttribute(
      'href',
      '/forgot-password',
    );
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
  });

  it('shows the same message with no form and no request when the token is missing', async () => {
    renderApp('/reset-password');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This reset link is invalid or has expired.',
    );
    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument();
    expect(identityServer.requests).toHaveLength(0);
  });
});

describe('Account and sign-out (FR-005, FR-010)', () => {
  beforeEach(() => {
    identityServer.signedIn = true;
  });

  it('shows the account, changes the display name and shows the platform field error next to it', async () => {
    const user = userEvent.setup();
    renderApp('/account', shopper);
    expect(await screen.findByRole('heading', { level: 1, name: 'Your account' })).toBeVisible();
    expect(await screen.findByText(ANA.email)).toBeVisible();
    const name = screen.getByLabelText('Display name');
    await waitFor(() => {
      expect(name).toHaveValue('Ana Silva');
    });
    expect(screen.getByRole('link', { name: 'Your addresses' })).toHaveAttribute(
      'href',
      '/account/addresses',
    );
    expect(screen.getByRole('link', { name: 'Notification preferences' })).toHaveAttribute(
      'href',
      '/account/notifications',
    );

    await user.clear(name);
    await user.type(name, 'Ana M. Silva');
    await user.click(screen.getByRole('button', { name: 'Save name' }));
    expect(await screen.findByText('Your name was saved.')).toBeVisible();
    expect(identityServer.displayName).toBe('Ana M. Silva');

    await user.clear(name);
    await user.type(name, 'A'.repeat(101));
    await user.click(screen.getByRole('button', { name: 'Save name' }));
    expect(await screen.findByText('must be at most 100 characters')).toBeVisible();
    expect(name).toHaveAccessibleDescription('must be at most 100 characters');
    expect(identityServer.displayName).toBe('Ana M. Silva');
  });

  it('signs out, returns to the home page and leaves nothing of the account in the cache', async () => {
    const user = userEvent.setup();
    seedOrder({ id: OLDEST, createdAt: '2026-10-01T09:00:00Z' });
    const { harness, router } = renderApp('/orders', shopper);
    await screen.findByRole('link', { name: 'Order 1a000000' });
    expect(harness.queryClient.getQueryCache().findAll({ queryKey: ['orders'] })).not.toHaveLength(
      0,
    );

    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeVisible();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/');
    });
    expect(harness.port.calls).toContain('signOut');
    expect(harness.sessionStore.current().state).toBe('anonymous');
    // Nothing the previous account loaded can be read any more (a page still mounted may have
    // started a fresh read, which holds no data).
    for (const key of ['orders', 'identity']) {
      const held = harness.queryClient.getQueryCache().findAll({ queryKey: [key] });
      expect(held.filter((query) => query.state.data !== undefined)).toEqual([]);
    }
  });

  it('asks for the password before deleting, refuses a wrong one, then signs out and says goodbye', async () => {
    const user = userEvent.setup();
    const { harness } = renderApp('/account', shopper);
    await user.click(await screen.findByRole('button', { name: 'Delete my account' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete your account?' });
    const password = within(dialog).getByLabelText('Password');
    const confirm = within(dialog).getByRole('button', { name: 'Delete my account' });

    await user.click(confirm);
    expect(await within(dialog).findByText('Enter your password.')).toBeVisible();
    expect(identityServer.deleted).toBe(false);

    await user.type(password, 'a-wrong-passphrase');
    await user.click(confirm);
    expect(await within(dialog).findByText('The password is incorrect.')).toBeVisible();
    expect(identityServer.deleted).toBe(false);
    expect(harness.sessionStore.current().state).toBe('signedIn');
    expect(identityServer.requests.filter((r) => r.method === 'DELETE')).toHaveLength(0);

    await user.clear(password);
    await user.type(password, ANA.password);
    await user.click(confirm);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Your account was deleted' }),
    ).toBeVisible();
    expect(screen.getByRole('status')).toHaveTextContent('You are signed out');
    expect(screen.getByRole('link', { name: 'Back to the store' })).toHaveAttribute('href', '/');
    expect(identityServer.deleted).toBe(true);
    expect(harness.port.calls).toContain('signOut');
    expect(harness.sessionStore.current().state).toBe('anonymous');
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeVisible();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows the platform refusal when the account cannot be deleted (403) and stays signed in', async () => {
    const user = userEvent.setup();
    server.use(
      http.delete(ME_URL, () =>
        problemResponse(403, 'forbidden', 'Forbidden', 'Operator accounts cannot be deleted here.'),
      ),
    );
    const { harness } = renderApp('/account', shopper);
    await user.click(await screen.findByRole('button', { name: 'Delete my account' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete your account?' });
    await user.type(within(dialog).getByLabelText('Password'), ANA.password);
    await user.click(within(dialog).getByRole('button', { name: 'Delete my account' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Operator accounts cannot be deleted here.',
    );
    expect(harness.sessionStore.current().state).toBe('signedIn');
  });

  it('keeps the account when the shopper backs out of the dialog', async () => {
    const user = userEvent.setup();
    renderApp('/account', shopper);
    await user.click(await screen.findByRole('button', { name: 'Delete my account' }));
    await user.click(screen.getByRole('button', { name: 'Keep my account' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(identityServer.deleted).toBe(false);
  });
});
