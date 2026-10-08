import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { type CheckoutDraft, EMPTY_DRAFT } from '@app/checkout/checkoutDraft';
import { DRAFT_STORAGE_KEY, loadDraft, saveDraft } from '@app/checkout/draftStorage';
import { isCanonicalUuidV4 } from '@domain/ids';

import { renderApp } from './render.tsx';
import { cartServer } from '../msw/cart.ts';
import { rake, spade } from '../msw/catalog.ts';
import { homeAddress, identityServer, workAddress } from '../msw/identity.ts';
import { orderServer } from '../msw/order.ts';

const shopper = { kind: 'signedIn', roles: ['shopper'] } as const;

/** A draft ready for the review step, as a shopper who went through the steps would have it. */
function reviewDraft(paymentMethodId: CheckoutDraft['paymentMethodId'] = 'tok_sim_approve_4242') {
  saveDraft(window.sessionStorage, {
    step: 'review',
    addressId: homeAddress.id,
    paymentMethodId,
  });
}

function storedDraft(): Record<string, unknown> {
  return JSON.parse(window.sessionStorage.getItem(DRAFT_STORAGE_KEY) ?? '{}') as Record<
    string,
    unknown
  >;
}

describe('Checkout steps (FR-006)', () => {
  it('picks a saved address and a payment method, reviews the amounts and places the order', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress, workAddress];
    cartServer.seed([
      { productId: rake.id, quantity: 2 },
      { productId: spade.id, quantity: 1 },
    ]);
    const { router } = renderApp('/checkout', shopper);
    expect(await screen.findByRole('heading', { level: 1, name: 'Checkout' })).toBeInTheDocument();
    const steps = screen.getByRole('list', { name: 'Checkout steps' });
    expect(within(steps).getByText('Delivery address')).toHaveAttribute('aria-current', 'step');

    const continueToPayment = await screen.findByRole('button', { name: 'Continue to payment' });
    expect(continueToPayment).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: /Work/ }));
    expect(continueToPayment).toBeEnabled();
    await user.click(continueToPayment);
    await waitFor(() => {
      expect(router.state.location.search).toBe('?step=payment');
    });

    expect(await screen.findByText(/Local development payment methods/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/card number/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    const continueToReview = screen.getByRole('button', { name: 'Continue to review' });
    expect(continueToReview).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: /Simulated card that is approved/ }));
    await user.click(continueToReview);
    await waitFor(() => {
      expect(router.state.location.search).toBe('?step=review');
    });

    const summary = await screen.findByRole('table', { name: 'Items in your order' });
    expect(within(summary).getByRole('row', { name: /Rake/ })).toHaveTextContent('R$20.00');
    expect(within(summary).getByRole('row', { name: /Spade/ })).toHaveTextContent('R$20.00');
    expect(screen.getByText('Total').parentElement).toHaveTextContent('R$40.00');
    expect(screen.getByText(/Deliver to/).parentElement).toHaveTextContent(
      'Ana Silva, Avenida da Liberdade 100, 1250-096 Lisboa, PT',
    );
    expect(screen.getByText(/Pay with/).parentElement).toHaveTextContent(
      'Simulated card that is approved',
    );
    // The draft in sessionStorage holds ids and the step only.
    const revision = cartServer.snapshot().revision;
    await waitFor(() => {
      expect(storedDraft()).toEqual({
        step: 'review',
        addressId: workAddress.id,
        paymentMethodId: 'tok_sim_approve_4242',
        acknowledgedRevision: revision,
      });
    });

    await user.click(screen.getByRole('button', { name: 'Confirm order' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/^\/orders\/[0-9a-f-]{36}\/confirmation$/);
    });
    expect(orderServer.placeRequests).toHaveLength(1);
    const [placed] = orderServer.placeRequests;
    expect(isCanonicalUuidV4(placed?.key ?? '')).toBe(true);
    expect(placed?.body).toEqual({
      addressId: workAddress.id,
      cartRevision: revision,
      paymentMethod: { type: 'card', token: 'tok_sim_approve_4242' },
    });

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Order confirmed' }),
    ).toBeInTheDocument();
    const orderId = router.state.location.pathname.split('/')[2]!;
    expect(screen.getByText('Order number').parentElement).toHaveTextContent(orderId.slice(0, 8));
    expect(screen.getByText(orderId)).toBeInTheDocument();
    expect(screen.getByText('Order status').nextElementSibling).toHaveTextContent('Placed');
    expect(screen.getByText('Payment status').nextElementSibling).toHaveTextContent('Approved');
    expect(screen.getByRole('table', { name: 'Items' })).toHaveTextContent('Rake');
    expect(screen.getByText('Your cart is now empty.')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByLabelText('0 items in cart')).toBeInTheDocument();
    });
    expect(loadDraft(window.sessionStorage)).toBe(EMPTY_DRAFT);
  });

  it('saves a new address through identity first and references it by id; server field errors sit next to the field', async () => {
    const user = userEvent.setup();
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    renderApp('/checkout', shopper);
    expect(
      await screen.findByText('You have no saved address yet. Add one below.'),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add a new address' }));
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
    expect(await within(form).findByText('is not a valid postal code')).toBeInTheDocument();
    expect(within(form).getByLabelText('Postal code')).toHaveAccessibleDescription(
      'is not a valid postal code',
    );

    await user.clear(within(form).getByLabelText('Postal code'));
    await user.type(within(form).getByLabelText('Postal code'), '1000-001');
    await user.click(within(form).getByRole('button', { name: 'Save address' }));
    const saved = await screen.findByRole('radio', { name: /Ana Silva/ });
    expect(saved).toBeChecked();
    expect(screen.queryByRole('form', { name: 'New delivery address' })).not.toBeInTheDocument();
    const posted = identityServer.requests.filter((r) => r.method === 'POST').at(-1);
    expect(posted?.body).toMatchObject({ line1: 'Rua das Flores 12', countryCode: 'PT' });
    const draft = storedDraft();
    expect(draft['addressId']).toBe(identityServer.addresses[0]?.id);
    expect(JSON.stringify(draft)).not.toContain('Rua das Flores');
    expect(JSON.stringify(draft)).not.toContain('Ana Silva');
    expect(screen.getByRole('button', { name: 'Continue to payment' })).toBeEnabled();
  });
});

describe('Checkout refusals and retries (FR-007, FR-008)', () => {
  it('409 price-changed lists the old and new price per line and requires the explicit acceptance before a resubmission with the current revision and a new key', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    cartServer.seed([{ productId: rake.id, quantity: 2, priceAtAdd: 1000 }]);
    cartServer.changePrice(rake.id, 1200);
    const currentCartRevision = cartServer.snapshot().revision;
    orderServer.refuseNextWith({ kind: 'priceChanged', currentCartRevision });
    reviewDraft();
    const { router } = renderApp('/checkout?step=review', shopper);
    const confirm = await screen.findByRole('button', { name: 'Confirm order' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);

    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('Prices changed');
    expect(notice).toHaveTextContent('Rake: was R$10.00, now R$12.00');
    expect(screen.getByRole('button', { name: 'Confirm order' })).toBeDisabled();
    expect(orderServer.placeRequests).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Accept the new prices' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Confirm order' })).toBeEnabled();
    });
    await user.click(screen.getByRole('button', { name: 'Confirm order' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/\/confirmation$/);
    });
    expect(orderServer.placeRequests).toHaveLength(2);
    const [first, second] = orderServer.placeRequests;
    expect(second?.key).not.toBe(first?.key);
    expect((second?.body as { cartRevision: string }).cartRevision).toBe(currentCartRevision);
  });

  it('409 insufficient-stock names the lines and flags them in the cart', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    cartServer.seed([
      { productId: rake.id, quantity: 2 },
      { productId: spade.id, quantity: 1 },
    ]);
    orderServer.refuseNextWith({ kind: 'insufficientStock', productIds: [rake.id] });
    reviewDraft();
    const { router } = renderApp('/checkout?step=review', shopper);
    const confirm = await screen.findByRole('button', { name: 'Confirm order' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);
    const heading = await screen.findByRole('heading', {
      name: 'Some items are no longer available',
    });
    const notice = heading.closest('[role="alert"]')!;
    expect(notice).toHaveTextContent('Rake: requested 2, available 0');
    expect(notice).not.toHaveTextContent('Spade');
    await user.click(screen.getByRole('link', { name: 'Adjust your cart' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/cart');
    });
    const rakeLine = await screen.findByRole('listitem', { name: 'Rake' });
    expect(within(rakeLine).getByText(/No longer available/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Check out' })).not.toBeInTheDocument();
  });

  it('422 payment-declined explains the refusal and keeps the cart for another method', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    orderServer.refuseNextWith({ kind: 'paymentDeclined', declineReason: 'card_rejected' });
    reviewDraft('tok_sim_decline_0001');
    const { router } = renderApp('/checkout?step=review', shopper);
    const confirm = await screen.findByRole('button', { name: 'Confirm order' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);
    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent('Your payment was declined');
    expect(notice).toHaveTextContent('the card was rejected');
    expect(notice).toHaveTextContent('your cart is kept');
    expect(cartServer.lines).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: 'Choose another payment method' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?step=payment');
    });
    expect(await screen.findByRole('radio', { name: /declined/ })).toBeChecked();
    expect(screen.getByLabelText('1 items in cart')).toBeInTheDocument();
  });

  it('a pending payment (202) shows "awaiting payment" with the countdown from paymentExpiresAt and refreshes every 5 s until the status is final', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    reviewDraft('tok_sim_unreachable');
    const { router } = renderApp('/checkout?step=review', shopper);
    const confirm = await screen.findByRole('button', { name: 'Confirm order' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Order received' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Payment status').nextElementSibling).toHaveTextContent(
      'Awaiting payment',
    );
    const timer = screen.getByRole('timer');
    expect(timer).toHaveTextContent(/Awaiting payment: (29|30):[0-5]\d left/);
    expect(screen.queryByText('Your cart is now empty.')).not.toBeInTheDocument();

    const orderId = router.state.location.pathname.split('/')[2]!;
    const order = orderServer.orders.get(orderId)!;
    orderServer.orders.set(orderId, {
      ...order,
      paymentStatus: 'approved',
      paymentExpiresAt: null,
      paymentAttemptId: 'c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50',
    });
    await waitFor(
      () => {
        expect(screen.getByText('Payment status').nextElementSibling).toHaveTextContent('Approved');
      },
      { timeout: 9_000 },
    );
    expect(orderServer.reads.filter((id) => id === orderId).length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByRole('timer')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Order confirmed' })).toBeInTheDocument();
  }, 15_000);

  it('a double click sends one request; a lost answer is retried manually with the same Idempotency-Key', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    orderServer.refuseNextWith({ kind: 'network' });
    reviewDraft();
    const { router } = renderApp('/checkout?step=review', shopper);
    const confirm = await screen.findByRole('button', { name: 'Confirm order' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.dblClick(confirm);
    const failure = await screen.findByRole('alert');
    expect(failure).toHaveTextContent('Your confirmation did not reach the store');
    expect(orderServer.placeRequests).toHaveLength(1);

    await user.click(screen.getByRole('button', { name: 'Send it again' }));
    await waitFor(() => {
      expect(router.state.location.pathname).toMatch(/\/confirmation$/);
    });
    expect(orderServer.placeRequests).toHaveLength(2);
    expect(orderServer.placeRequests[1]?.key).toBe(orderServer.placeRequests[0]?.key);
    expect(orderServer.placeRequests[1]?.body).toEqual(orderServer.placeRequests[0]?.body);
    expect(orderServer.orders.size).toBe(1);
  });

  it('a 401 while confirming returns to sign-in with the checkout as the next step, and the draft is restored afterwards', async () => {
    const user = userEvent.setup();
    identityServer.addresses = [homeAddress];
    cartServer.seed([{ productId: rake.id, quantity: 1 }]);
    orderServer.refuseNextWith({ kind: 'unauthorized' });
    reviewDraft();
    const { router, unmount } = renderApp('/checkout?step=review', shopper);
    const confirm = await screen.findByRole('button', { name: 'Confirm order' });
    await waitFor(() => {
      expect(confirm).toBeEnabled();
    });
    await user.click(confirm);
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/sign-in');
    });
    expect(router.state.location.search).toBe('?next=%2Fcheckout%3Fstep%3Dreview');
    expect(storedDraft()).toMatchObject({
      step: 'review',
      addressId: homeAddress.id,
      paymentMethodId: 'tok_sim_approve_4242',
    });
    unmount();

    // Signed in again: the same review step, with the draft intact.
    renderApp('/checkout?step=review', shopper);
    expect(await screen.findByText(/Deliver to/)).toBeInTheDocument();
    expect(screen.getByText(/Pay with/).parentElement).toHaveTextContent(
      'Simulated card that is approved',
    );
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Confirm order' })).toBeEnabled();
    });
  });

  it('shows the empty state on the review step when the cart is empty', async () => {
    identityServer.addresses = [homeAddress];
    reviewDraft();
    renderApp('/checkout?step=review', shopper);
    expect(await screen.findByRole('heading', { name: 'Your cart is empty' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm order' })).not.toBeInTheDocument();
  });
});
