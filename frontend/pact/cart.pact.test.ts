import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createCartApi } from '@api/cart';
import { ProblemError } from '@api/problem';
import { Quantity } from '@domain/quantity';

import {
  asGateway,
  bearer,
  CART,
  CART_TOKEN,
  instant,
  LINE_1,
  MISSING_ID,
  money,
  PRODUCT,
  problem,
  PROBLEM,
  uuid,
} from './gateway.ts';
import { pactFor } from './pact.config.ts';

// Storefront → cart consumer pact: interactions K1–K7 of contracts/pact-matrix.md, provider states
// verbatim, driving the real src/api/cart.ts. The cart service sees the request as the gateway
// forwards it (`X-Cart-Token` from the cart cookie or a bearer from the session cookie, injected
// by the stand-in of gateway.ts); the storefront itself never holds either credential.
const { boolean, eachLike, integer, like, string } = MatchersV3;

const CART_PATH = '/api/v1/cart';
const LINES = '/api/v1/cart/lines';
const MERGE = '/api/v1/cart/merge';

function quantityOf(value: number) {
  const parsed = Quantity.parse(value);
  if (!parsed.ok) throw new Error(`fixture quantity ${value}`);
  return parsed.value;
}

function line(quantity = 2, options: { priceChanged?: boolean } = {}) {
  const changed = options.priceChanged ?? false;
  return {
    id: uuid(LINE_1),
    productId: uuid(PRODUCT),
    productName: string('Trail Running Shoes'),
    quantity: integer(quantity),
    priceAtAdd: money(8990),
    currentPrice: money(changed ? 9490 : 8990),
    priceChanged: boolean(changed),
    lineTotal: money((changed ? 9490 : 8990) * quantity),
  };
}

function cart(lines: number, options: { quantity?: number; priceChanged?: boolean } = {}) {
  const quantity = options.quantity ?? 2;
  return {
    id: uuid(CART),
    revision: string(options.priceChanged === true ? 'rev-b21e5d08' : 'rev-7f3a9c21'),
    lines: lines === 0 ? [] : eachLike(line(quantity, options), lines),
    total: money(lines === 0 ? 0 : 8990 * quantity * lines),
    updatedAt: instant('2026-10-02T10:20:00Z'),
  };
}

const insufficientStock = {
  ...problem(
    'insufficient-stock',
    422,
    'Insufficient stock',
    'Only 0 units are available; requested 2.',
  ),
  errors: eachLike({ field: string('quantity'), message: like('available quantity: 0') }),
};

const provider = pactFor('cart');

function anonymous(mockServerUrl: string, cartToken?: string) {
  return createCartApi({
    baseUrl: mockServerUrl,
    fetch: asGateway(cartToken === undefined ? {} : { cartToken }),
  });
}

function signedIn(mockServerUrl: string, cartToken?: string) {
  return createCartApi({
    baseUrl: mockServerUrl,
    fetch: asGateway({ bearer: true, ...(cartToken === undefined ? {} : { cartToken }) }),
  });
}

describe('storefront → cart pact (K1–K7)', () => {
  describe('K1 getCart', () => {
    it('reads an anonymous cart through its token', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving('a read of the anonymous cart tok-cart-1')
        .withRequest('GET', CART_PATH, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(2));
        })
        .executeTest(async (mockServer) => {
          const found = await anonymous(mockServer.url, CART_TOKEN).getCart();
          expect(found?.id).toBe(CART);
          expect(found?.lines).toHaveLength(2);
          expect(found?.revision).toBe('rev-7f3a9c21');
        });
    });

    it('reads the account cart of a signed-in shopper', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com has an account cart with 1 line')
        .uponReceiving('a read of the account cart of ana@example.com')
        .withRequest('GET', CART_PATH, (request) => {
          request.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(1));
        })
        .executeTest(async (mockServer) => {
          const found = await signedIn(mockServer.url).getCart();
          expect(found?.lines).toHaveLength(1);
          expect(found?.total.amountMinor).toBe(17980);
        });
    });

    it('reads an empty cart when there is no token', async () => {
      await provider
        .addInteraction()
        .given('no anonymous cart exists')
        .uponReceiving('a read of the cart without any token')
        .withRequest('GET', CART_PATH)
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(0));
        })
        .executeTest(async (mockServer) => {
          const found = await anonymous(mockServer.url).getCart();
          expect(found?.lines).toEqual([]);
          expect(found?.total.amountMinor).toBe(0);
        });
    });

    it('treats an unknown or expired token as no cart', async () => {
      await provider
        .addInteraction()
        .given('no cart exists for token tok-unknown')
        .uponReceiving('a read of the cart with the unknown token tok-unknown')
        .withRequest('GET', CART_PATH, (request) => {
          request.headers({ 'X-Cart-Token': 'tok-unknown' });
        })
        .willRespondWith(404, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('not-found', 404, 'Not found', 'Cart token is unknown or expired.'));
        })
        .executeTest(async (mockServer) => {
          expect(await anonymous(mockServer.url, 'tok-unknown').getCart()).toBeNull();
        });
    });
  });

  describe('K2 addCartLine', () => {
    it('starts an anonymous cart on the first write; the token travels in the response header', async () => {
      await provider
        .addInteraction()
        .given('no anonymous cart exists')
        .uponReceiving('a first anonymous addition of 2 units of a product')
        .withRequest('POST', LINES, (request) => {
          request.jsonBody({ productId: PRODUCT, quantity: 2 });
        })
        .willRespondWith(201, (response) => {
          response.headers({ 'X-Cart-Token': like(CART_TOKEN) }).jsonBody(cart(1));
        })
        .executeTest(async (mockServer) => {
          const updated = await anonymous(mockServer.url).addLine(PRODUCT, quantityOf(2));
          expect(updated.lines[0]?.productId).toBe(PRODUCT);
          expect(updated.lines[0]?.quantity).toBe(2);
        });
    });

    it('adds to an existing anonymous cart', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving('an addition of 1 unit to the anonymous cart tok-cart-1')
        .withRequest('POST', LINES, (request) => {
          request
            .headers({ 'X-Cart-Token': CART_TOKEN })
            .jsonBody({ productId: PRODUCT, quantity: 1 });
        })
        .willRespondWith(201, (response) => {
          response.jsonBody(cart(2, { quantity: 3 }));
        })
        .executeTest(async (mockServer) => {
          const updated = await anonymous(mockServer.url, CART_TOKEN).addLine(
            PRODUCT,
            quantityOf(1),
          );
          expect(updated.lines).toHaveLength(2);
        });
    });

    it('refuses an unknown product with 404', async () => {
      await provider
        .addInteraction()
        .given(`product ${MISSING_ID} does not exist`)
        .uponReceiving(`an addition of the unknown product ${MISSING_ID}`)
        .withRequest('POST', LINES, (request) => {
          request.jsonBody({ productId: MISSING_ID, quantity: 1 });
        })
        .willRespondWith(404, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('not-found', 404, 'Not found', 'Product not found.'));
        })
        .executeTest(async (mockServer) => {
          const failure = anonymous(mockServer.url).addLine(MISSING_ID, quantityOf(1));
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
          await failure.catch((error: unknown) => {
            expect((error as ProblemError).problem.status).toBe(404);
          });
        });
    });

    it('refuses a product without stock with 422 and the available quantity', async () => {
      await provider
        .addInteraction()
        .given(`product ${PRODUCT} is active with stock 0`)
        .uponReceiving(`an addition of the out-of-stock product ${PRODUCT}`)
        .withRequest('POST', LINES, (request) => {
          request.jsonBody({ productId: PRODUCT, quantity: 2 });
        })
        .willRespondWith(422, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(insufficientStock);
        })
        .executeTest(async (mockServer) => {
          const failure = anonymous(mockServer.url).addLine(PRODUCT, quantityOf(2));
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
          await failure.catch((error: unknown) => {
            const { problem: refused } = error as ProblemError;
            expect(refused.status).toBe(422);
            expect(refused.type).toBe('insufficient-stock');
            expect(refused.errors[0]?.field).toBe('quantity');
          });
        });
    });
  });

  describe('K3 updateCartLineQuantity', () => {
    it('sets a quantity and receives the recalculated cart', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving(`a quantity of 3 for line ${LINE_1}`)
        .withRequest('PUT', `${LINES}/${LINE_1}`, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN }).jsonBody({ quantity: 3 });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(2, { quantity: 3 }));
        })
        .executeTest(async (mockServer) => {
          const updated = await anonymous(mockServer.url, CART_TOKEN).setLineQuantity(
            LINE_1,
            quantityOf(3),
          );
          expect(updated.lines[0]?.quantity).toBe(3);
          expect(updated.total.amountMinor).toBe(8990 * 3 * 2);
        });
    });

    it('removes the line with a quantity of 0', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving(`a quantity of 0 for line ${LINE_1}`)
        .withRequest('PUT', `${LINES}/${LINE_1}`, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN }).jsonBody({ quantity: 0 });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(1));
        })
        .executeTest(async (mockServer) => {
          const updated = await anonymous(mockServer.url, CART_TOKEN).setLineQuantity(
            LINE_1,
            Quantity.zero,
          );
          expect(updated.lines).toHaveLength(1);
        });
    });

    it('refuses a quantity above the available stock with 422', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving(`a quantity of 99 for line ${LINE_1} above the stock`)
        .withRequest('PUT', `${LINES}/${LINE_1}`, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN }).jsonBody({ quantity: 99 });
        })
        .willRespondWith(422, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(insufficientStock);
        })
        .executeTest(async (mockServer) => {
          const failure = anonymous(mockServer.url, CART_TOKEN).setLineQuantity(
            LINE_1,
            quantityOf(99),
          );
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
        });
    });
  });

  describe('K4 removeCartLine', () => {
    it('removes a line and receives the recalculated cart', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving(`a removal of line ${LINE_1}`)
        .withRequest('DELETE', `${LINES}/${LINE_1}`, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(1));
        })
        .executeTest(async (mockServer) => {
          const updated = await anonymous(mockServer.url, CART_TOKEN).removeLine(LINE_1);
          expect(updated.lines).toHaveLength(1);
        });
    });

    it('answers 404 for an unknown line', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving(`a removal of the unknown line ${MISSING_ID}`)
        .withRequest('DELETE', `${LINES}/${MISSING_ID}`, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(404, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('not-found', 404, 'Not found', 'Cart line not found.'));
        })
        .executeTest(async (mockServer) => {
          await expect(
            anonymous(mockServer.url, CART_TOKEN).removeLine(MISSING_ID),
          ).rejects.toBeInstanceOf(ProblemError);
        });
    });
  });

  describe('K5 clearCart', () => {
    it('empties the cart', async () => {
      await provider
        .addInteraction()
        .given('an anonymous cart with token tok-cart-1 holds 2 lines')
        .uponReceiving('a request to empty the anonymous cart tok-cart-1')
        .withRequest('DELETE', CART_PATH, (request) => {
          request.headers({ 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(204)
        .executeTest(async (mockServer) => {
          await expect(anonymous(mockServer.url, CART_TOKEN).clearCart()).resolves.toBeUndefined();
        });
    });
  });

  describe('K6 mergeCart', () => {
    it('merges the anonymous cart into the account cart and reports the capped lines', async () => {
      await provider
        .addInteraction()
        .given(
          'an anonymous cart with token tok-cart-1 holds 2 lines and ana@example.com has an account cart',
        )
        .uponReceiving(
          'a merge of the anonymous cart tok-cart-1 after the sign-in of ana@example.com',
        )
        .withRequest('POST', MERGE, (request) => {
          request.headers({ Authorization: bearer(), 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            cart: cart(2, { quantity: 5 }),
            cappedLines: eachLike({
              productId: uuid(PRODUCT),
              requestedQuantity: integer(7),
              appliedQuantity: integer(5),
            }),
          });
        })
        .executeTest(async (mockServer) => {
          const merged = await signedIn(mockServer.url, CART_TOKEN).mergeCart();
          expect(merged?.cart.lines).toHaveLength(2);
          expect(merged?.cappedLines[0]).toEqual({
            productId: PRODUCT,
            requestedQuantity: 7,
            appliedQuantity: 5,
          });
        });
    });

    it('treats an already merged token as nothing to merge', async () => {
      await provider
        .addInteraction()
        .given('the anonymous cart tok-cart-1 was already merged')
        .uponReceiving('a second merge of the consumed token tok-cart-1')
        .withRequest('POST', MERGE, (request) => {
          request.headers({ Authorization: bearer(), 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(404, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('not-found', 404, 'Not found', 'Cart token is unknown or expired.'));
        })
        .executeTest(async (mockServer) => {
          expect(await signedIn(mockServer.url, CART_TOKEN).mergeCart()).toBeNull();
        });
    });

    it('surfaces a merge in progress as a conflict to retry', async () => {
      await provider
        .addInteraction()
        .given('a merge for tok-cart-1 is in progress')
        .uponReceiving('a merge of tok-cart-1 while another merge of it runs')
        .withRequest('POST', MERGE, (request) => {
          request.headers({ Authorization: bearer(), 'X-Cart-Token': CART_TOKEN });
        })
        .willRespondWith(409, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('conflict', 409, 'Conflict', 'A merge of this cart is in progress.'));
        })
        .executeTest(async (mockServer) => {
          const failure = signedIn(mockServer.url, CART_TOKEN).mergeCart();
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
          await failure.catch((error: unknown) => {
            expect((error as ProblemError).problem.status).toBe(409);
          });
        });
    });
  });

  describe('K7 getCart after a price change', () => {
    it('flags the line whose price changed and carries the new revision', async () => {
      await provider
        .addInteraction()
        .given('the price of a line in the cart of ana@example.com changed since it was added')
        .uponReceiving('a read of the account cart of ana@example.com after a price change')
        .withRequest('GET', CART_PATH, (request) => {
          request.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(cart(1, { priceChanged: true }));
        })
        .executeTest(async (mockServer) => {
          const found = await signedIn(mockServer.url).getCart();
          expect(found?.revision).toBe('rev-b21e5d08');
          expect(found?.lines[0]?.priceChanged).toBe(true);
          expect(found?.lines[0]?.priceAtAdd.amountMinor).toBe(8990);
          expect(found?.lines[0]?.currentPrice.amountMinor).toBe(9490);
        });
    });
  });
});
