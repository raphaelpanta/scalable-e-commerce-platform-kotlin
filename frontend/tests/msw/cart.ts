import { http, HttpResponse } from 'msw';

import type { Cart, CartLine, MergeResult } from '@app/cart/cartPort';

import { API, PROBLEM, products } from './catalog.ts';

// An in-memory cart behind MSW for the component tests, answering like the cart contract as the
// gateway forwards it (no token or cookie is visible to the page): one cart for the whole test,
// quantities capped at the fixture stock (5 units for a product in stock, none when sold out),
// a revision that changes with every write, totals computed here because the server owns them.
export const CART_URL = `${API}/api/v1/cart`;
export const CART_LINES_URL = `${API}/api/v1/cart/lines`;
export const CART_MERGE_URL = `${API}/api/v1/cart/merge`;
export const FIXTURE_STOCK = 5;

type Line = { id: string; productId: string; quantity: number; priceAtAdd: number };

type CartServer = {
  lines: Line[];
  revision: number;
  /** Per product: the current price (changeable by a test to flag `priceChanged`). */
  currentPrices: Map<string, number>;
  /** What the next merge answers: capped lines, or a status to refuse with. */
  nextMerge: { cappedLines: MergeResult['cappedLines'] } | { status: 400 | 404 | 409 };
  /** Every request the fake received, for assertions on shape and count. */
  requests: Array<{ method: string; url: string; body: unknown }>;
  reset(): void;
  seed(items: ReadonlyArray<{ productId: string; quantity: number; priceAtAdd?: number }>): void;
  changePrice(productId: string, amountMinor: number): void;
  snapshot(): Cart;
};

let lineSequence = 0;

function priceOf(productId: string): number {
  return products.find((p) => p.id === productId)?.price.amountMinor ?? 0;
}

function stockOf(productId: string): number {
  const product = products.find((p) => p.id === productId);
  if (product === undefined) return 0;
  return product.availability.inStock ? FIXTURE_STOCK : 0;
}

export const cartServer: CartServer = {
  lines: [],
  revision: 1,
  currentPrices: new Map(),
  nextMerge: { cappedLines: [] },
  requests: [],
  reset() {
    this.lines = [];
    this.revision = 1;
    this.currentPrices = new Map();
    this.nextMerge = { cappedLines: [] };
    this.requests = [];
  },
  seed(items) {
    for (const item of items) {
      lineSequence += 1;
      this.lines.push({
        id: `0000${String(lineSequence).padStart(4, '0')}-1111-4222-8333-444444444444`,
        productId: item.productId,
        quantity: item.quantity,
        priceAtAdd: item.priceAtAdd ?? priceOf(item.productId),
      });
    }
    this.revision += 1;
  },
  changePrice(productId, amountMinor) {
    this.currentPrices.set(productId, amountMinor);
    this.revision += 1;
  },
  snapshot() {
    const lines: CartLine[] = this.lines.map((line) => {
      const current = this.currentPrices.get(line.productId) ?? priceOf(line.productId);
      return {
        id: line.id,
        productId: line.productId,
        productName: products.find((p) => p.id === line.productId)?.name ?? 'Unknown',
        quantity: line.quantity,
        priceAtAdd: { amountMinor: line.priceAtAdd, currency: 'BRL' },
        currentPrice: { amountMinor: current, currency: 'BRL' },
        priceChanged: current !== line.priceAtAdd,
        lineTotal: { amountMinor: current * line.quantity, currency: 'BRL' },
      };
    });
    return {
      id: '8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34',
      revision: `rev-${String(this.revision)}`,
      lines,
      total: {
        amountMinor: lines.reduce((sum, line) => sum + line.lineTotal.amountMinor, 0),
        currency: 'BRL',
      },
      updatedAt: '2026-10-02T10:20:00Z',
    };
  },
};

function problem(slug: string, status: number, title: string, detail: string, extra = {}) {
  return HttpResponse.json(
    { type: `https://ecommerce.example/problems/${slug}`, title, status, detail, ...extra },
    { status, headers: { 'Content-Type': PROBLEM } },
  );
}

function insufficientStock(available: number, requested: number) {
  return problem(
    'insufficient-stock',
    422,
    'Insufficient stock',
    `Only ${String(available)} units are available; requested ${String(requested)}.`,
    { errors: [{ field: 'quantity', message: `available quantity: ${String(available)}` }] },
  );
}

async function record(request: Request): Promise<unknown> {
  const text = request.method === 'GET' || request.method === 'DELETE' ? '' : await request.text();
  const body: unknown = text === '' ? undefined : JSON.parse(text);
  cartServer.requests.push({ method: request.method, url: request.url, body });
  return body;
}

export const cartHandlers = [
  http.get(CART_URL, async ({ request }) => {
    await record(request);
    return HttpResponse.json(cartServer.snapshot());
  }),
  http.delete(CART_URL, async ({ request }) => {
    await record(request);
    cartServer.lines = [];
    cartServer.revision += 1;
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(CART_LINES_URL, async ({ request }) => {
    const body = (await record(request)) as { productId: string; quantity: number };
    const product = products.find((p) => p.id === body.productId);
    if (product === undefined) return problem('not-found', 404, 'Not found', 'Product not found.');
    const existing = cartServer.lines.find((line) => line.productId === body.productId);
    const requested = (existing?.quantity ?? 0) + body.quantity;
    const stock = stockOf(body.productId);
    if (requested > stock) return insufficientStock(stock, requested);
    if (existing === undefined)
      cartServer.seed([{ productId: body.productId, quantity: body.quantity }]);
    else existing.quantity = requested;
    cartServer.revision += 1;
    return HttpResponse.json(cartServer.snapshot(), { status: 201 });
  }),
  http.put(`${CART_LINES_URL}/:lineId`, async ({ request, params }) => {
    const body = (await record(request)) as { quantity: number };
    const line = cartServer.lines.find((l) => l.id === params['lineId']);
    if (line === undefined) return problem('not-found', 404, 'Not found', 'Cart line not found.');
    const stock = stockOf(line.productId);
    if (body.quantity > stock) return insufficientStock(stock, body.quantity);
    if (body.quantity === 0) cartServer.lines = cartServer.lines.filter((l) => l.id !== line.id);
    else line.quantity = body.quantity;
    cartServer.revision += 1;
    return HttpResponse.json(cartServer.snapshot());
  }),
  http.delete(`${CART_LINES_URL}/:lineId`, async ({ request, params }) => {
    await record(request);
    const line = cartServer.lines.find((l) => l.id === params['lineId']);
    if (line === undefined) return problem('not-found', 404, 'Not found', 'Cart line not found.');
    cartServer.lines = cartServer.lines.filter((l) => l.id !== line.id);
    cartServer.revision += 1;
    return HttpResponse.json(cartServer.snapshot());
  }),
  http.post(CART_MERGE_URL, async ({ request }) => {
    await record(request);
    const next = cartServer.nextMerge;
    if ('status' in next) {
      return problem(
        next.status === 409 ? 'conflict' : next.status === 404 ? 'not-found' : 'validation',
        next.status,
        'Merge refused',
        'The anonymous cart could not be merged.',
      );
    }
    const result: MergeResult = { cart: cartServer.snapshot(), cappedLines: next.cappedLines };
    return HttpResponse.json(result);
  }),
];
