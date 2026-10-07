import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createOrderApi } from '@api/order';
import type { PlaceOrderRequest } from '@app/order/orderPort';
import { IdempotencyKey } from '@domain/ids';

import {
  ADA,
  ADDRESS_OWNED,
  asGateway,
  BEANS,
  bearer,
  ESPRESSO,
  instant,
  KEY_1,
  KEY_2,
  LINE_1,
  money,
  ORDER_1,
  problem,
  PROBLEM,
  uuid,
  UUID_PATTERN,
} from './gateway.ts';
import { pactFor } from './pact.config.ts';

// Storefront → order consumer pact: interactions O1–O8 of contracts/pact-matrix.md,
// provider states verbatim, driving the real src/api/order.ts with the bearer the gateway injects.
// `paymentExpiresAt` (the additive field of the order contract) is asserted while the payment is
// pending (O5, O7) and null once it is approved (O1); a pending checkout is the contract's 202.
const { eachLike, integer, like, regex, string } = MatchersV3;

const ORDERS = '/api/v1/orders';
const REVISION = 'rev-7f3a9c21';
const APPROVE = 'tok_sim_approve_4242';
const DECLINE = 'tok_sim_decline_0001';
const UNREACHABLE = 'tok_sim_unreachable';
const DECLINE_REASON =
  '^(insufficient_funds|card_expired|card_rejected|suspected_fraud|invalid_payment_method)$';
const CANCELLED_WHILE_CHARGING =
  'an order of ana@example.com was cancelled while its payment was being processed';

function keyOf(value: string): IdempotencyKey {
  const parsed = IdempotencyKey.parse(value);
  if (!parsed.ok) throw new Error(`fixture key ${value}`);
  return parsed.value;
}

function request(token = APPROVE, revision = REVISION): PlaceOrderRequest {
  return {
    addressId: ADDRESS_OWNED,
    cartRevision: revision,
    paymentMethod: { type: 'card', token },
  };
}

function lines() {
  return [
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
  ];
}

function order(payment: 'approved' | 'pending') {
  return {
    id: uuid(ORDER_1),
    orderStatus: 'placed',
    paymentStatus: payment,
    cancellationReason: null,
    lines: lines(),
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
    ...(payment === 'approved'
      ? { paymentAttemptId: uuid('c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50'), paymentExpiresAt: null }
      : { paymentAttemptId: null, paymentExpiresAt: instant('2026-10-02T10:45:00Z') }),
  };
}

const provider = pactFor('order');

const shopper = (url: string) =>
  createOrderApi({ baseUrl: url, fetch: asGateway({ bearer: true }) });

describe('storefront → order pact (O1–O8)', () => {
  describe('O1 placeOrder', () => {
    it('places an order with an approved payment under a mandatory Idempotency-Key', async () => {
      await provider
        .addInteraction()
        .given(
          `ana@example.com has a cart at revision ${REVISION} with stock available and owns address ${ADDRESS_OWNED}`,
        )
        .uponReceiving('a checkout of ana@example.com with the approving method')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': regex(UUID_PATTERN, KEY_1) })
            .jsonBody(request());
        })
        .willRespondWith(201, (response) => {
          response.jsonBody(order('approved'));
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(request(), keyOf(KEY_1));
          expect(result.kind).toBe('placed');
          if (result.kind !== 'placed') return;
          expect(result.order.id).toBe(ORDER_1);
          expect(result.order.orderStatus).toBe('placed');
          expect(result.order.paymentStatus).toBe('approved');
          expect(result.order.paymentExpiresAt).toBeNull();
          expect(result.order.lines).toHaveLength(2);
        });
    });
  });

  describe('O2 placeOrder replay', () => {
    it('replays the same 201 for the same key and body (one order)', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com already placed an order with Idempotency-Key key-1')
        .uponReceiving('the same checkout of ana@example.com sent again with key-1')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': KEY_1 })
            .jsonBody(request());
        })
        .willRespondWith(201, (response) => {
          response.jsonBody(order('approved'));
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(request(), keyOf(KEY_1));
          expect(result.kind).toBe('placed');
          if (result.kind === 'placed') expect(result.order.id).toBe(ORDER_1);
        });
    });
  });

  describe('O3 placeOrder conflicts', () => {
    it('refuses a checkout after a price change with the old and new price per line', async () => {
      await provider
        .addInteraction()
        .given(`the price of a line changed after revision ${REVISION}`)
        .uponReceiving(`a checkout of ana@example.com with the stale revision ${REVISION}`)
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': regex(UUID_PATTERN, KEY_1) })
            .jsonBody(request());
        })
        .willRespondWith(409, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem(
              'price-changed',
              409,
              'Price changed',
              'The price of one or more items changed since you last viewed the cart.',
            ),
            changedLines: eachLike({
              lineId: uuid(LINE_1),
              productId: uuid(ESPRESSO),
              oldPrice: money(14900),
              newPrice: money(15900),
            }),
            currentCartRevision: string('rev-9d4c1b37'),
          });
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(request(), keyOf(KEY_1));
          expect(result).toEqual({
            kind: 'priceChanged',
            changedLines: [
              {
                lineId: LINE_1,
                productId: ESPRESSO,
                oldPrice: { amountMinor: 14900, currency: 'BRL' },
                newPrice: { amountMinor: 15900, currency: 'BRL' },
              },
            ],
            currentCartRevision: 'rev-9d4c1b37',
          });
        });
    });

    it('refuses a checkout naming the lines whose stock is insufficient', async () => {
      await provider
        .addInteraction()
        .given('a line in the cart of ana@example.com exceeds available stock')
        .uponReceiving('a checkout of ana@example.com with a line above the stock')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': regex(UUID_PATTERN, KEY_1) })
            .jsonBody(request());
        })
        .willRespondWith(409, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem(
              'insufficient-stock',
              409,
              'Insufficient stock',
              'One or more items in the cart are no longer available.',
            ),
            unavailableLines: eachLike({
              productId: uuid(BEANS),
              name: string('Coffee Beans 1kg'),
              requestedQuantity: integer(2),
              availableQuantity: integer(0),
            }),
          });
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(request(), keyOf(KEY_1));
          expect(result).toEqual({
            kind: 'insufficientStock',
            unavailableLines: [
              {
                productId: BEANS,
                name: 'Coffee Beans 1kg',
                requestedQuantity: 2,
                availableQuantity: 0,
              },
            ],
          });
        });
    });

    it('reports an order cancelled while its payment was being processed', async () => {
      await provider
        .addInteraction()
        .given(CANCELLED_WHILE_CHARGING)
        .uponReceiving('a checkout of ana@example.com whose order was cancelled during the charge')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': regex(UUID_PATTERN, KEY_1) })
            .jsonBody(request());
        })
        .willRespondWith(409, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem(
              'order-cancelled',
              409,
              'Order cancelled',
              'The order was cancelled while its payment was being processed.',
            ),
            orderId: uuid(ORDER_1),
            cancellationReason: regex(
              '^(SHOPPER_REQUEST|OPERATOR|PAYMENT_EXPIRED)$',
              'SHOPPER_REQUEST',
            ),
          });
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(request(), keyOf(KEY_1));
          expect(result).toEqual({
            kind: 'orderCancelled',
            orderId: ORDER_1,
            cancellationReason: 'SHOPPER_REQUEST',
          });
        });
    });
  });

  describe('O4 placeOrder unprocessable', () => {
    it('explains a declined payment with its category; the order is recorded cancelled', async () => {
      await provider
        .addInteraction()
        .given('payment is declined for token tok_sim_decline_01')
        .uponReceiving('a checkout of ana@example.com with the declining method')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': regex(UUID_PATTERN, KEY_2) })
            .jsonBody(request(DECLINE));
        })
        .willRespondWith(422, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem(
              'payment-declined',
              422,
              'Payment declined',
              'The payment provider declined the charge.',
            ),
            declineReason: regex(DECLINE_REASON, 'card_rejected'),
            orderId: uuid('0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12'),
          });
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(request(DECLINE), keyOf(KEY_2));
          expect(result).toEqual({
            kind: 'paymentDeclined',
            declineReason: 'card_rejected',
            orderId: '0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12',
          });
        });
    });

    it('refuses the same key with a different body', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com already placed an order with Idempotency-Key key-1')
        .uponReceiving('a different checkout of ana@example.com sent with key-1')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': KEY_1 })
            .jsonBody(request(APPROVE, 'rev-9d4c1b37'));
        })
        .willRespondWith(422, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem(
              'idempotency-key-reuse',
              422,
              'Idempotency key reused with a different request',
              'The Idempotency-Key was already used with a different request body.',
            ),
            idempotencyConflict: true,
          });
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(
            request(APPROVE, 'rev-9d4c1b37'),
            keyOf(KEY_1),
          );
          expect(result).toEqual({ kind: 'idempotencyConflict' });
        });
    });
  });

  describe('O5 placeOrder with a pending payment', () => {
    it('places the order with paymentStatus pending and the end of the payment window', async () => {
      await provider
        .addInteraction()
        .given('payment is pending for token tok_sim_unreachable')
        .uponReceiving('a checkout of ana@example.com while the payment provider is unreachable')
        .withRequest('POST', ORDERS, (builder) => {
          builder
            .headers({ Authorization: bearer(), 'Idempotency-Key': regex(UUID_PATTERN, KEY_2) })
            .jsonBody(request(UNREACHABLE));
        })
        .willRespondWith(202, (response) => {
          response.jsonBody(order('pending'));
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).placeOrder(
            request(UNREACHABLE),
            keyOf(KEY_2),
          );
          expect(result.kind).toBe('placed');
          if (result.kind !== 'placed') return;
          expect(result.order.paymentStatus).toBe('pending');
          expect(result.order.paymentExpiresAt).toBe('2026-10-02T10:45:00Z');
          expect(result.order.paymentAttemptId).toBeNull();
        });
    });
  });

  describe('O7 getOwnOrder', () => {
    it('reads an own order with its status history', async () => {
      await provider
        .addInteraction()
        .given(`ana@example.com owns order ${ORDER_1}`)
        .uponReceiving(`a read of order ${ORDER_1} by its owner`)
        .withRequest('GET', `${ORDERS}/${ORDER_1}`, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(order('approved'));
        })
        .executeTest(async (mockServer) => {
          const found = await shopper(mockServer.url).getOwnOrder(ORDER_1);
          expect(found?.id).toBe(ORDER_1);
          expect(found?.statusHistory.length).toBeGreaterThan(0);
          expect(found?.paymentExpiresAt).toBeNull();
        });
    });

    it('reads a pending payment with its deadline (polled until it becomes approved)', async () => {
      await provider
        .addInteraction()
        .given(`the payment of order ${ORDER_1} is pending and becomes approved`)
        .uponReceiving(`a poll of order ${ORDER_1} while its payment is pending`)
        .withRequest('GET', `${ORDERS}/${ORDER_1}`, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(order('pending'));
        })
        .executeTest(async (mockServer) => {
          const found = await shopper(mockServer.url).getOwnOrder(ORDER_1);
          expect(found?.paymentStatus).toBe('pending');
          expect(found?.paymentExpiresAt).toBe('2026-10-02T10:45:00Z');
        });
    });

    it("treats another shopper's order as not found", async () => {
      await provider
        .addInteraction()
        .given(`order ${ORDER_1} belongs to another shopper`)
        .uponReceiving(`a read of order ${ORDER_1} by a shopper who does not own it`)
        .withRequest('GET', `${ORDERS}/${ORDER_1}`, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(404, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('not-found', 404, 'Not found', 'Order not found.'));
        })
        .executeTest(async (mockServer) => {
          expect(await shopper(mockServer.url).getOwnOrder(ORDER_1)).toBeNull();
        });
    });
  });

  describe('O6 listOwnOrders', () => {
    it('lists a page of the own orders, newest first', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com has 3 orders')
        .uponReceiving('a read of the orders of ana@example.com')
        .withRequest('GET', ORDERS, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: eachLike(order('approved'), 3),
            page: integer(0),
            size: integer(20),
            totalItems: integer(3),
          });
        })
        .executeTest(async (mockServer) => {
          const page = await shopper(mockServer.url).listOwnOrders();
          expect(page.totalItems).toBe(3);
          expect(page.items).toHaveLength(3);
          expect(page.items[0]?.orderStatus).toBe('placed');
          expect(page.items[0]?.paymentStatus).toBe('approved');
        });
    });

    it('answers an empty page when the shopper has no order', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com has no orders')
        .uponReceiving('a read of the orders of ana@example.com when there are none')
        .withRequest('GET', ORDERS, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: [],
            page: integer(0),
            size: integer(20),
            totalItems: integer(0),
          });
        })
        .executeTest(async (mockServer) => {
          const page = await shopper(mockServer.url).listOwnOrders();
          expect(page.items).toEqual([]);
        });
    });
  });

  describe('O8 cancelOwnOrder', () => {
    it('cancels a placed order at the shopper request', async () => {
      await provider
        .addInteraction()
        .given(`ana@example.com owns a placed order ${ORDER_1}`)
        .uponReceiving(`a cancellation of the placed order ${ORDER_1}`)
        .withRequest('POST', `${ORDERS}/${ORDER_1}/cancellation`, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            ...order('approved'),
            orderStatus: 'cancelled',
            paymentStatus: regex('^(approved|pending|failed)$', 'approved'),
            cancellationReason: 'SHOPPER_REQUEST',
            paymentAttemptId: like('c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50'),
          });
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).cancelOwnOrder(ORDER_1);
          expect(result.kind).toBe('cancelled');
          if (result.kind !== 'cancelled') return;
          expect(result.order.orderStatus).toBe('cancelled');
          expect(result.order.cancellationReason).toBe('SHOPPER_REQUEST');
        });
    });

    it('refuses a shipped order with 409 order-not-cancellable and the reason', async () => {
      await provider
        .addInteraction()
        .given(`ana@example.com owns a shipped order ${ORDER_1}`)
        .uponReceiving(`a cancellation of the shipped order ${ORDER_1}`)
        .withRequest('POST', `${ORDERS}/${ORDER_1}/cancellation`, (builder) => {
          builder.headers({ Authorization: bearer() });
        })
        .willRespondWith(409, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(
              problem(
                'order-not-cancellable',
                409,
                'Order cannot be cancelled',
                'Shoppers can only cancel an order while it is placed; this order is shipped.',
              ),
            );
        })
        .executeTest(async (mockServer) => {
          const result = await shopper(mockServer.url).cancelOwnOrder(ORDER_1);
          expect(result.kind).toBe('notCancellable');
          if (result.kind !== 'notCancellable') return;
          expect(result.message).toContain('shipped');
        });
    });
  });
});
