import { http, HttpResponse } from 'msw';

import type { Product } from '@app/catalog/catalogPort';
import type { Order } from '@app/order/orderPort';
import { OrderStatus } from '@domain/status';

import { PRODUCTS_URL, PROBLEM, rake, soldOutLamp } from './catalog.ts';
import { ORDERS_URL, orderServer } from './order.ts';

// The operator's side of the platform behind MSW for the console component tests: the listing of
// every order with the `orderStatus` filter, the status transition under the lifecycle rules of
// feature 004 (a refused one is a 409 `invalid-transition`), the catalogue listing with
// withdrawn products and the stock adjustment with its 422s. Orders live in `orderServer.orders`
// so the order page reads what a transition changed. The list handler answers only while a test
// armed it (`consoleServer.serveList`), so it never shadows another suite's own listing.
export const STOCK_URL = (productId: string) => `${PRODUCTS_URL}/${productId}/stock-adjustments`;

export const withdrawnKettle: Product = {
  id: '5a9dbc6b-7fac-4bd8-a465-bcdf8dac0a76',
  name: 'Withdrawn kettle',
  description: 'No longer sold.',
  price: { amountMinor: 8000, currency: 'BRL' },
  categoryId: rake.categoryId,
  status: 'withdrawn',
  images: [],
  availability: { inStock: false },
  createdAt: '2026-10-02T08:00:00Z',
  updatedAt: '2026-10-02T08:00:00Z',
};

type Adjustment = { productId: string; delta: number; reason: string };

type ConsoleServer = {
  /** Answer `GET /api/v1/orders` like the platform does for an operator. */
  serveList: boolean;
  /** Answer every operator request with 403, like a platform that does not know the role. */
  forbidAll: boolean;
  /** Units on hand per product id. */
  stock: Map<string, number>;
  /** The query of every order listing the console made. */
  listings: URLSearchParams[];
  transitions: Array<{ orderId: string; orderStatus: string }>;
  adjustments: Adjustment[];
  productRequests: URLSearchParams[];
  /** Answers the next transition with this 409 instead of applying it. */
  refuseNextTransition: string | undefined;
  reset(): void;
};

const initialStock = (): Map<string, number> =>
  new Map([
    [rake.id, 10],
    [soldOutLamp.id, 0],
    [withdrawnKettle.id, 0],
  ]);

export const consoleServer: ConsoleServer = {
  serveList: false,
  forbidAll: false,
  stock: initialStock(),
  listings: [],
  transitions: [],
  adjustments: [],
  productRequests: [],
  refuseNextTransition: undefined,
  reset() {
    this.serveList = false;
    this.forbidAll = false;
    this.stock = initialStock();
    this.listings = [];
    this.transitions = [];
    this.adjustments = [];
    this.productRequests = [];
    this.refuseNextTransition = undefined;
  },
};

function problem(slug: string, status: number, title: string, detail: string, extra = {}) {
  return HttpResponse.json(
    { type: `https://ecommerce.example/problems/${slug}`, title, status, detail, ...extra },
    { status, headers: { 'Content-Type': PROBLEM } },
  );
}

const forbidden = () =>
  problem('forbidden', 403, 'Forbidden', 'This operation requires the operator role.');

const OPERATOR_ACCOUNT = 'e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22';
const OPERATOR_STEPS = ['preparing', 'shipped', 'delivered'] as const;

/** An order as the console reads it: two lines, a delivery address, a history of what happened. */
export function consoleOrder(
  id: string,
  orderStatus: Order['orderStatus'],
  paymentStatus: Order['paymentStatus'],
  createdAt = '2026-10-02T10:15:00Z',
): Order {
  const cancelled = orderStatus === 'cancelled';
  return {
    id,
    orderStatus,
    paymentStatus,
    cancellationReason: cancelled ? 'OPERATOR' : null,
    lines: [
      {
        productId: rake.id,
        name: rake.name,
        unitPrice: { amountMinor: 1000, currency: 'BRL' },
        quantity: 2,
        lineTotal: { amountMinor: 2000, currency: 'BRL' },
      },
    ],
    total: { amountMinor: 2000, currency: 'BRL' },
    deliveryAddress: {
      recipientName: 'Ana Silva',
      line1: 'Rua das Flores 12',
      line2: null,
      city: 'Lisboa',
      postalCode: '1000-001',
      country: 'PT',
    },
    statusHistory: [
      {
        kind: 'order',
        status: 'placed',
        at: createdAt,
        by: '7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d',
      },
      { kind: 'payment', status: 'pending', at: createdAt, by: 'system' },
      ...(paymentStatus === 'pending'
        ? []
        : [{ kind: 'payment' as const, status: paymentStatus, at: createdAt, by: 'system' }]),
      ...OPERATOR_STEPS.slice(0, OPERATOR_STEPS.indexOf(orderStatus as never) + 1).map((step) => ({
        kind: 'order' as const,
        status: step,
        at: createdAt,
        by: OPERATOR_ACCOUNT,
      })),
      ...(cancelled
        ? [
            {
              kind: 'order' as const,
              status: 'cancelled' as const,
              at: createdAt,
              by: OPERATOR_ACCOUNT,
            },
          ]
        : []),
    ],
    createdAt,
    paymentAttemptId: paymentStatus === 'approved' ? 'c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50' : null,
    paymentExpiresAt:
      paymentStatus === 'pending' ? new Date(Date.now() + 20 * 60_000).toISOString() : null,
  };
}

/** Puts the orders in the order service, newest first as the platform lists them. */
export function seedOrders(...orders: Order[]): void {
  for (const order of orders) orderServer.orders.set(order.id, order);
}

function listOrders(request: Request) {
  const search = new URL(request.url).searchParams;
  const wanted = search.get('orderStatus');
  const found = [...orderServer.orders.values()]
    .filter((order) => wanted === null || order.orderStatus === wanted)
    .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  const page = Number(search.get('page') ?? '0');
  const size = Number(search.get('size') ?? '20');
  return HttpResponse.json({
    items: found.slice(page * size, page * size + size),
    page,
    size,
    totalItems: found.length,
  });
}

export const consoleHandlers = [
  http.get(ORDERS_URL, ({ request }) => {
    if (!consoleServer.serveList) return undefined;
    consoleServer.listings.push(new URL(request.url).searchParams);
    return consoleServer.forbidAll ? forbidden() : listOrders(request);
  }),
  http.post(`${ORDERS_URL}/:orderId/status`, async ({ params, request }) => {
    const orderId = String(params['orderId']);
    const body = (await request.json()) as { orderStatus: string };
    consoleServer.transitions.push({ orderId, orderStatus: body.orderStatus });
    if (consoleServer.forbidAll) return forbidden();
    const order = orderServer.orders.get(orderId);
    if (order === undefined) return problem('not-found', 404, 'Not found', 'Order not found.');
    if (consoleServer.refuseNextTransition !== undefined) {
      const detail = consoleServer.refuseNextTransition;
      consoleServer.refuseNextTransition = undefined;
      return problem('invalid-transition', 409, 'Invalid status transition', detail);
    }
    const allowed =
      OrderStatus.allowedNext(order.orderStatus).includes(body.orderStatus as OrderStatus) &&
      (body.orderStatus !== 'preparing' || order.paymentStatus === 'approved');
    if (!allowed) {
      return problem(
        'invalid-transition',
        409,
        'Invalid status transition',
        `Cannot move an order from ${order.orderStatus} to ${body.orderStatus}.`,
      );
    }
    const moved: Order = {
      ...order,
      orderStatus: body.orderStatus as Order['orderStatus'],
      cancellationReason: body.orderStatus === 'cancelled' ? 'OPERATOR' : null,
    };
    orderServer.orders.set(orderId, moved);
    return HttpResponse.json(moved);
  }),
  http.get(PRODUCTS_URL, ({ request }) => {
    const search = new URL(request.url).searchParams;
    if (search.get('includeWithdrawn') !== 'true') return undefined;
    consoleServer.productRequests.push(search);
    if (consoleServer.forbidAll) return forbidden();
    const q = search.get('q')?.toLowerCase() ?? '';
    const visible = [rake, soldOutLamp, withdrawnKettle]
      .filter((product) => q === '' || product.name.toLowerCase().includes(q))
      .map((product) => ({
        ...product,
        availability: {
          inStock: (consoleServer.stock.get(product.id) ?? 0) > 0,
          availableQuantity: consoleServer.stock.get(product.id) ?? 0,
        },
      }));
    const page = Number(search.get('page') ?? '0');
    const size = Number(search.get('size') ?? '20');
    return HttpResponse.json({
      items: visible.slice(page * size, page * size + size),
      page,
      size,
      totalItems: visible.length,
    });
  }),
  http.post(`${PRODUCTS_URL}/:productId/stock-adjustments`, async ({ params, request }) => {
    const productId = String(params['productId']);
    const body = (await request.json()) as { delta: number; reason: string };
    consoleServer.adjustments.push({ productId, ...body });
    if (consoleServer.forbidAll) return forbidden();
    const before = consoleServer.stock.get(productId);
    if (before === undefined) return problem('not-found', 404, 'Not found', 'Product not found.');
    const errors = [
      ...(before + body.delta < 0
        ? [{ field: 'delta', message: 'would make available quantity negative' }]
        : []),
      ...(body.reason.length === 0 ? [{ field: 'reason', message: 'must not be blank' }] : []),
    ];
    if (errors.length > 0) {
      return problem('validation', 422, 'Validation failed', 'The request is not valid.', {
        errors,
      });
    }
    consoleServer.stock.set(productId, before + body.delta);
    return HttpResponse.json(
      {
        id: '4e7a2c91-0d63-4b58-a1f4-9c3e8b5d2a70',
        productId,
        delta: body.delta,
        reason: body.reason,
        previousQuantity: before,
        newQuantity: before + body.delta,
        adjustedBy: '3f2b8c0e-5d41-4a39-9c1e-0a7f6b2d4e11',
        adjustedAt: '2026-10-02T10:00:00Z',
      },
      { status: 201 },
    );
  }),
];
