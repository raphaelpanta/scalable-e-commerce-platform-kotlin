import { http, HttpResponse, type RequestHandler } from 'msw';

import { cartServer } from '../../tests/msw/cart.ts';
import { lantern, products, rake } from '../../tests/msw/catalog.ts';
import { consoleOrder, consoleServer, seedOrders } from '../../tests/msw/console.ts';
import { homeAddress, identityServer, ME_URL } from '../../tests/msw/identity.ts';
import { OWNER_ID, seedOrder } from '../../tests/msw/order.ts';
import { paymentServer } from '../../tests/msw/payment.ts';

// Deterministic state of the visual harness, chosen by `?scenario=` on the first page load (the
// fakes live in the page, so every `page.goto` starts from the same seed):
// - `anonymous` (default): the catalogue only;
// - `shopper`: signed in as ANA, a cart of two lines (rake × 1, lantern × 2), one saved address,
//   three past orders in mixed statuses and one placed order for the confirmation page, with the
//   payment attempts of those orders;
// - `operator`: the shopper's state plus the operator role and the console's listings, for the
//   console pages.
export const SCENARIOS = ['anonymous', 'shopper', 'operator'] as const;
export type Scenario = (typeof SCENARIOS)[number];

/** The placed order the confirmation page shows (`/orders/<id>/confirmation`). */
export const CONFIRMED_ORDER_ID = '0b9a3b0e-62b7-4f55-8d7e-00000000c0f1';
const DELIVERED_ORDER_ID = '0b9a3b0e-62b7-4f55-8d7e-00000000a001';
const SHIPPED_ORDER_ID = '0b9a3b0e-62b7-4f55-8d7e-00000000a002';
const CANCELLED_ORDER_ID = '0b9a3b0e-62b7-4f55-8d7e-00000000a003';
const APPROVED_ATTEMPT_ID = 'c2f1d0a9-5b3e-4e7a-9a60-00000000a0a1';
const DECLINED_ATTEMPT_ID = 'c2f1d0a9-5b3e-4e7a-9a60-00000000a0a2';
const FIXTURE_HOST = 'https://cdn.example.test/';

export function scenarioOf(search: string): Scenario {
  const wanted = new URLSearchParams(search).get('scenario');
  return SCENARIOS.find((scenario) => scenario === wanted) ?? 'anonymous';
}

/** Product images come from the harness origin (visual/public/visual-fixtures), never remote. */
function sameOriginImages(): void {
  for (const product of products) {
    for (const image of product.images) {
      image.url = image.url.replace(FIXTURE_HOST, '/visual-fixtures/');
    }
  }
}

function history(
  createdAt: string,
  ...steps: ReadonlyArray<'preparing' | 'shipped' | 'delivered'>
) {
  return [
    { kind: 'order' as const, status: 'placed' as const, at: createdAt, by: OWNER_ID },
    { kind: 'payment' as const, status: 'pending' as const, at: createdAt, by: 'system' },
    { kind: 'payment' as const, status: 'approved' as const, at: createdAt, by: 'system' },
    ...steps.map((status) => ({ kind: 'order' as const, status, at: createdAt, by: 'operator' })),
  ];
}

function seedShopper(): void {
  identityServer.signedIn = true;
  identityServer.addresses = [homeAddress];
  cartServer.seed([
    { productId: rake.id, quantity: 1 },
    { productId: lantern.id, quantity: 2 },
  ]);
  seedOrder({
    id: DELIVERED_ORDER_ID,
    createdAt: '2026-01-05T09:00:00Z',
    orderStatus: 'delivered',
    paymentStatus: 'approved',
    statusHistory: history('2026-01-05T09:00:00Z', 'preparing', 'shipped', 'delivered'),
  });
  seedOrder({
    id: SHIPPED_ORDER_ID,
    createdAt: '2026-01-10T14:30:00Z',
    orderStatus: 'shipped',
    paymentStatus: 'approved',
    statusHistory: history('2026-01-10T14:30:00Z', 'preparing', 'shipped'),
  });
  seedOrder({
    id: CANCELLED_ORDER_ID,
    createdAt: '2026-01-12T18:45:00Z',
    orderStatus: 'cancelled',
    paymentStatus: 'failed',
    cancellationReason: 'PAYMENT_FAILED',
    paymentAttemptId: DECLINED_ATTEMPT_ID,
  });
  seedOrder({
    id: CONFIRMED_ORDER_ID,
    createdAt: '2026-01-15T09:55:00Z',
    orderStatus: 'placed',
    paymentStatus: 'approved',
    paymentAttemptId: APPROVED_ATTEMPT_ID,
  });
  paymentServer.attempts = [
    {
      id: DECLINED_ATTEMPT_ID,
      orderId: CANCELLED_ORDER_ID,
      amount: { amountMinor: 2000, currency: 'BRL' },
      outcome: 'declined',
      declineReason: 'insufficient_funds',
      idempotencyKey: '2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81',
      createdAt: '2026-01-12T18:45:01Z',
    },
    {
      id: APPROVED_ATTEMPT_ID,
      orderId: CONFIRMED_ORDER_ID,
      amount: { amountMinor: 2000, currency: 'BRL' },
      outcome: 'approved',
      idempotencyKey: '3c4d5e6f-7081-4a92-83c4-d5e6f70a8192',
      createdAt: '2026-01-15T09:55:01Z',
    },
  ];
}

function seedOperator(): RequestHandler[] {
  seedShopper();
  consoleServer.serveList = true;
  seedOrders(
    consoleOrder(
      '6d1e2f3a-4b5c-4d6e-8f70-00000000b001',
      'placed',
      'approved',
      '2026-01-14T08:00:00Z',
    ),
    consoleOrder(
      '6d1e2f3a-4b5c-4d6e-8f70-00000000b002',
      'preparing',
      'approved',
      '2026-01-13T08:00:00Z',
    ),
  );
  return [
    http.get(ME_URL, () =>
      HttpResponse.json({
        id: OWNER_ID,
        email: 'ana@example.com',
        displayName: 'Ana Silva',
        emailVerified: true,
        roles: ['shopper', 'operator'],
        createdAt: '2026-01-02T09:15:00Z',
      }),
    ),
  ];
}

/** Seeds the fakes for the scenario; returns the handlers that take precedence over the defaults. */
export function seedScenario(scenario: Scenario): RequestHandler[] {
  sameOriginImages();
  switch (scenario) {
    case 'anonymous':
      return [];
    case 'shopper':
      seedShopper();
      return [];
    case 'operator':
      return seedOperator();
  }
}
