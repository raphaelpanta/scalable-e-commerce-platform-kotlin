import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createOrderApi } from '@api/order';
import { createOrderConsoleApi } from '@api/orderConsole';

import {
  ADA,
  asGateway,
  BEANS,
  bearer,
  ESPRESSO,
  instant,
  money,
  ORDER_1,
  problem,
  PROBLEM,
  uuid,
} from './gateway.ts';
import { pactFor } from './pact.config.ts';

// Storefront → order consumer pact, the operator console's rows O9–O11 of contracts/pact-matrix.md,
// provider states verbatim, driving the real src/api/orderConsole.ts (and getOwnOrder of
// src/api/order.ts) with the operator's bearer the gateway injects. They merge into
// build/pacts/storefront-order.json next to the shopper rows of order.pact.test.ts. O9 needs the
// additive order change of plan.md "Operator order listing" (operator role accepted, optional
// `orderStatus` filter); a shopper's own listing is O6, so there is no 403 row for the list.
const { eachLike, integer, like, regex, string } = MatchersV3;

const ORDERS = '/api/v1/orders';
const OPERATOR_WITH_FIVE =
  'an operator ops@example.com is signed in and 5 orders of 2 shoppers exist';
const OPERATOR_WITH_ORDER_1 = `an operator ops@example.com is signed in and order ${ORDER_1} exists`;
const SHOPPER_SIGNED_IN = 'a shopper ana@example.com is signed in';

function orderBody(orderStatus: string, paymentStatus: 'approved' | 'pending' = 'approved') {
  return {
    id: uuid(ORDER_1),
    orderStatus,
    paymentStatus,
    cancellationReason: null,
    lines: [
      {
        productId: uuid(ESPRESSO),
        name: string('Espresso Machine'),
        unitPrice: money(14900),
        quantity: integer(1),
        lineTotal: money(14900),
      },
      {
        productId: uuid(BEANS),
        name: string('Coffee Beans 1kg'),
        unitPrice: money(2450),
        quantity: integer(2),
        lineTotal: money(4900),
      },
    ],
    total: money(19800),
    deliveryAddress: {
      recipientName: string('Ada Lovelace'),
      line1: string('12 Analytical Street'),
      line2: null,
      city: string('London'),
      postalCode: string('N1 9GU'),
      country: regex('^[A-Z]{2}$', 'GB'),
    },
    statusHistory: eachLike({
      kind: regex('^(order|payment)$', 'order'),
      status: like('placed'),
      at: instant('2026-10-02T10:15:00Z'),
      by: like(ADA),
    }),
    createdAt: instant('2026-10-02T10:15:00Z'),
    ...(paymentStatus === 'approved'
      ? { paymentAttemptId: uuid('c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50'), paymentExpiresAt: null }
      : { paymentAttemptId: null, paymentExpiresAt: instant('2026-10-02T10:45:00Z') }),
  };
}

const provider = pactFor('order');

const asOperator = (url: string) =>
  createOrderConsoleApi({ baseUrl: url, fetch: asGateway({ bearer: true }) });
const asShopperOrOperator = (url: string) =>
  createOrderApi({ baseUrl: url, fetch: asGateway({ bearer: true }) });

describe('storefront → order pact, console rows (O9–O11)', () => {
  describe('O9 listOwnOrders as an operator', () => {
    it('lists the orders of every shopper, newest first, with both statuses', async () => {
      await provider
        .addInteraction()
        .given(OPERATOR_WITH_FIVE)
        .uponReceiving('a console list of all orders, first page of 10')
        .withRequest('GET', ORDERS, (builder) => {
          builder.query({ page: '0', size: '10' }).headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: eachLike(orderBody('placed')),
            page: integer(0),
            size: integer(10),
            totalItems: 5,
          });
        })
        .executeTest(async (mockServer) => {
          const page = await asOperator(mockServer.url).listOrders({ page: 0, size: 10 });
          expect(page.totalItems).toBe(5);
          expect(page.items[0]?.orderStatus).toBe('placed');
          expect(page.items[0]?.paymentStatus).toBe('approved');
        });
    });

    it('narrows the list to one orderStatus', async () => {
      await provider
        .addInteraction()
        .given(OPERATOR_WITH_FIVE)
        .uponReceiving('a console list of the placed orders')
        .withRequest('GET', ORDERS, (builder) => {
          builder.query({ orderStatus: 'placed' }).headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: eachLike(orderBody('placed')),
            page: integer(0),
            size: integer(20),
            totalItems: 5,
          });
        })
        .executeTest(async (mockServer) => {
          const page = await asOperator(mockServer.url).listOrders({ orderStatus: 'placed' });
          expect(page.items.every((order) => order.orderStatus === 'placed')).toBe(true);
          expect(page.totalItems).toBe(5);
        });
    });
  });

  describe('O10 getOwnOrder as an operator', () => {
    it('reads any order', async () => {
      await provider
        .addInteraction()
        .given(OPERATOR_WITH_ORDER_1)
        .uponReceiving(`a console read of order ${ORDER_1}`)
        .withRequest('GET', `${ORDERS}/${ORDER_1}`, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(orderBody('placed'));
        })
        .executeTest(async (mockServer) => {
          const order = await asShopperOrOperator(mockServer.url).getOwnOrder(ORDER_1);
          expect(order?.id).toBe(ORDER_1);
          expect(order?.statusHistory.length).toBeGreaterThan(0);
        });
    });
  });

  describe('O11 transitionOrderStatus', () => {
    it('moves a placed order with an approved payment to preparing', async () => {
      await provider
        .addInteraction()
        .given(`order ${ORDER_1} is placed with payment approved`)
        .uponReceiving(`an operator moves order ${ORDER_1} to preparing`)
        .withRequest('POST', `${ORDERS}/${ORDER_1}/status`, (builder) => {
          builder.headers({ Authorization: bearer() }).jsonBody({ orderStatus: 'preparing' });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(orderBody('preparing'));
        })
        .executeTest(async (mockServer) => {
          const result = await asOperator(mockServer.url).transitionOrderStatus(
            ORDER_1,
            'preparing',
          );
          expect(result.kind).toBe('transitioned');
          if (result.kind !== 'transitioned') return;
          expect(result.order.orderStatus).toBe('preparing');
          expect(result.order.paymentStatus).toBe('approved');
        });
    });

    it('refuses a transition the lifecycle does not allow (409 invalid-transition)', async () => {
      await provider
        .addInteraction()
        .given(`order ${ORDER_1} is delivered`)
        .uponReceiving(`an operator moves the delivered order ${ORDER_1} back to preparing`)
        .withRequest('POST', `${ORDERS}/${ORDER_1}/status`, (builder) => {
          builder.headers({ Authorization: bearer() }).jsonBody({ orderStatus: 'preparing' });
        })
        .willRespondWith(409, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(
              problem(
                'invalid-transition',
                409,
                'Invalid status transition',
                'Cannot move an order from delivered to preparing.',
              ),
            );
        })
        .executeTest(async (mockServer) => {
          const result = await asOperator(mockServer.url).transitionOrderStatus(
            ORDER_1,
            'preparing',
          );
          expect(result.kind).toBe('refused');
          if (result.kind === 'refused') expect(result.message.length).toBeGreaterThan(0);
        });
    });

    it('refuses preparing while the payment is not approved (409 invalid-transition)', async () => {
      await provider
        .addInteraction()
        .given(`order ${ORDER_1} is placed with payment pending`)
        .uponReceiving(`an operator moves order ${ORDER_1} to preparing before its payment`)
        .withRequest('POST', `${ORDERS}/${ORDER_1}/status`, (builder) => {
          builder.headers({ Authorization: bearer() }).jsonBody({ orderStatus: 'preparing' });
        })
        .willRespondWith(409, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(
              problem(
                'invalid-transition',
                409,
                'Invalid status transition',
                'An order moves to preparing only while its payment is approved.',
              ),
            );
        })
        .executeTest(async (mockServer) => {
          const result = await asOperator(mockServer.url).transitionOrderStatus(
            ORDER_1,
            'preparing',
          );
          expect(result.kind).toBe('refused');
        });
    });

    it('refuses a shopper (403)', async () => {
      await provider
        .addInteraction()
        .given(SHOPPER_SIGNED_IN)
        .uponReceiving(`a shopper tries to move order ${ORDER_1} to shipped`)
        .withRequest('POST', `${ORDERS}/${ORDER_1}/status`, (builder) => {
          builder.headers({ Authorization: bearer() }).jsonBody({ orderStatus: 'shipped' });
        })
        .willRespondWith(403, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(
              problem('forbidden', 403, 'Forbidden', 'This operation requires the operator role.'),
            );
        })
        .executeTest(async (mockServer) => {
          const result = await asOperator(mockServer.url).transitionOrderStatus(ORDER_1, 'shipped');
          expect(result).toEqual({ kind: 'forbidden' });
        });
    });
  });
});
