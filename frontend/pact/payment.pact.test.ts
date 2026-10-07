import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createPaymentApi } from '@api/payment';

import { asGateway, ATTEMPT_DECLINED, bearer, instant, money, ORDER_1, uuid } from './gateway.ts';
import { pactFor } from './pact.config.ts';

// Storefront → payment consumer pact: interaction P2 of contracts/pact-matrix.md (the payment
// attempts of an order, newest first), provider states verbatim, driving the real
// src/api/payment.ts with the bearer the gateway injects. P1 (simulator rules) joins with US6.
const { eachLike, integer, like, regex } = MatchersV3;

const ATTEMPTS = '/api/v1/payments/attempts';

const provider = pactFor('payment');

const shopper = (url: string) =>
  createPaymentApi({ baseUrl: url, fetch: asGateway({ bearer: true }) });

describe('storefront → payment pact (P2)', () => {
  it('lists a declined attempt with its decline category', async () => {
    await provider
      .addInteraction()
      .given(`order ${ORDER_1} has a declined payment attempt`)
      .uponReceiving(`a read of the payment attempts of order ${ORDER_1}`)
      .withRequest('GET', ATTEMPTS, (request) => {
        request.query({ orderId: ORDER_1 }).headers({ Authorization: bearer() });
      })
      .willRespondWith(200, (response) => {
        response.jsonBody({
          items: eachLike({
            id: uuid(ATTEMPT_DECLINED),
            orderId: uuid(ORDER_1),
            amount: money(4913),
            outcome: regex('^(approved|declined|pending|voided)$', 'declined'),
            declineReason: regex(
              '^(insufficient_funds|card_expired|card_rejected|suspected_fraud|invalid_payment_method)$',
              'insufficient_funds',
            ),
            providerReference: like('sim_ch_000124'),
            idempotencyKey: uuid('2b3c4d5e-6f70-4a81-92b3-c4d5e6f70a81'),
            createdAt: instant('2026-10-02T10:30:00Z'),
          }),
          page: integer(0),
          size: integer(20),
          totalItems: integer(1),
        });
      })
      .executeTest(async (mockServer) => {
        const page = await shopper(mockServer.url).listAttempts(ORDER_1);
        expect(page.totalItems).toBe(1);
        expect(page.items[0]?.outcome).toBe('declined');
        expect(page.items[0]?.declineReason).toBe('insufficient_funds');
      });
  });

  it('answers an empty page for an order without attempts', async () => {
    await provider
      .addInteraction()
      .given(`order ${ORDER_1} has no payment attempts`)
      .uponReceiving(`a read of the payment attempts of order ${ORDER_1} when there are none`)
      .withRequest('GET', ATTEMPTS, (request) => {
        request.query({ orderId: ORDER_1 }).headers({ Authorization: bearer() });
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
        const page = await shopper(mockServer.url).listAttempts(ORDER_1);
        expect(page.items).toEqual([]);
      });
  });
});
