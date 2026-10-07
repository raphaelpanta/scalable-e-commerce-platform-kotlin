import { http, HttpResponse } from 'msw';

import type { Order } from '@app/order/orderPort';

import { cartServer } from './cart.ts';
import { API, PROBLEM } from './catalog.ts';
import { identityServer } from './identity.ts';

// The order service behind MSW for the component tests: a checkout freezes the current fake cart
// into an order (approved, or pending for the unreachable method with a payment window of 30
// minutes), replays the same answer for the same Idempotency-Key, and refuses as scripted by a
// test (`orderServer.refuseNextWith`). Orders are kept for the confirmation page to read.
export const ORDERS_URL = `${API}/api/v1/orders`;
export const PAYMENT_WINDOW_MS = 30 * 60_000;

export type ScriptedRefusal =
  | { kind: 'priceChanged'; currentCartRevision: string }
  | { kind: 'insufficientStock'; productIds: readonly string[] }
  | { kind: 'paymentDeclined'; declineReason: string }
  | { kind: 'orderCancelled' }
  | { kind: 'idempotencyConflict' }
  | { kind: 'unauthorized' }
  | { kind: 'network' }
  | { kind: 'hang' };

type PlaceRequest = { key: string | null; body: unknown };

type OrderServer = {
  orders: Map<string, Order>;
  replays: Map<string, { status: number; order: Order }>;
  script: ScriptedRefusal[];
  placeRequests: PlaceRequest[];
  reads: string[];
  /** The ids a cancellation was requested for, in order. */
  cancellations: string[];
  /** `createdAt` of the next order placed (the countdown tests pin the clock). */
  now: () => Date;
  reset(): void;
  refuseNextWith(...refusals: ScriptedRefusal[]): void;
};

let orderSequence = 0;

export const orderServer: OrderServer = {
  orders: new Map(),
  replays: new Map(),
  script: [],
  placeRequests: [],
  reads: [],
  cancellations: [],
  now: () => new Date(),
  reset() {
    this.orders = new Map();
    this.replays = new Map();
    this.script = [];
    this.placeRequests = [];
    this.reads = [];
    this.cancellations = [];
    this.now = () => new Date();
  },
  refuseNextWith(...refusals) {
    this.script.push(...refusals);
  },
};

function problem(slug: string, status: number, title: string, detail: string, extra = {}) {
  return HttpResponse.json(
    { type: `https://ecommerce.example/problems/${slug}`, title, status, detail, ...extra },
    { status, headers: { 'Content-Type': PROBLEM } },
  );
}

function orderFromCart(token: string, createdAt: Date): Order {
  orderSequence += 1;
  const cart = cartServer.snapshot();
  const pending = token === 'tok_sim_unreachable';
  const address = identityServer.addresses[0];
  return {
    id: `0b9a3b0e-62b7-4f55-8d7e-${String(orderSequence).padStart(12, '0')}`,
    orderStatus: 'placed',
    paymentStatus: pending ? 'pending' : 'approved',
    cancellationReason: null,
    lines: cart.lines.map((line) => ({
      productId: line.productId,
      name: line.productName,
      unitPrice: line.currentPrice,
      quantity: line.quantity,
      lineTotal: line.lineTotal,
    })),
    total: cart.total,
    deliveryAddress: {
      recipientName: address?.recipientName ?? 'Ana Silva',
      line1: address?.line1 ?? 'Rua das Flores 12',
      line2: null,
      city: address?.city ?? 'Lisboa',
      postalCode: address?.postalCode ?? '1000-001',
      country: address?.countryCode ?? 'PT',
    },
    statusHistory: [
      { kind: 'order' as const, status: 'placed' as const, at: createdAt.toISOString(), by: 'ana' },
      {
        kind: 'payment' as const,
        status: 'pending' as const,
        at: createdAt.toISOString(),
        by: 'system',
      },
      ...(pending
        ? []
        : [
            {
              kind: 'payment' as const,
              status: 'approved' as const,
              at: createdAt.toISOString(),
              by: 'system',
            },
          ]),
    ],
    createdAt: createdAt.toISOString(),
    paymentAttemptId: pending ? null : 'c2f1d0a9-5b3e-4e7a-9a60-8d1b2c3e4f50',
    paymentExpiresAt: pending
      ? new Date(createdAt.getTime() + PAYMENT_WINDOW_MS).toISOString()
      : null,
  };
}

function refuse(refusal: ScriptedRefusal) {
  const cart = cartServer.snapshot();
  switch (refusal.kind) {
    case 'priceChanged':
      return problem(
        'price-changed',
        409,
        'Price changed',
        'The price of one or more items changed since you last viewed the cart.',
        {
          changedLines: cart.lines
            .filter((line) => line.priceChanged)
            .map((line) => ({
              lineId: line.id,
              productId: line.productId,
              oldPrice: line.priceAtAdd,
              newPrice: line.currentPrice,
            })),
          currentCartRevision: refusal.currentCartRevision,
        },
      );
    case 'insufficientStock':
      return problem(
        'insufficient-stock',
        409,
        'Insufficient stock',
        'One or more items in the cart are no longer available.',
        {
          unavailableLines: cart.lines
            .filter((line) => refusal.productIds.includes(line.productId))
            .map((line) => ({
              productId: line.productId,
              name: line.productName,
              requestedQuantity: line.quantity,
              availableQuantity: 0,
            })),
        },
      );
    case 'paymentDeclined':
      return problem(
        'payment-declined',
        422,
        'Payment declined',
        'The payment provider declined the charge.',
        {
          declineReason: refusal.declineReason,
          orderId: '0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a12',
        },
      );
    case 'orderCancelled':
      return problem(
        'order-cancelled',
        409,
        'Order cancelled',
        'The order was cancelled while its payment was being processed.',
        { orderId: '0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a13', cancellationReason: 'SHOPPER_REQUEST' },
      );
    case 'idempotencyConflict':
      return problem(
        'idempotency-key-reuse',
        422,
        'Idempotency key reused with a different request',
        'The Idempotency-Key was already used with a different request body.',
        { idempotencyConflict: true },
      );
    case 'unauthorized':
      return problem('unauthorized', 401, 'Unauthorized', 'The session has expired.');
    case 'network':
      return HttpResponse.error();
    case 'hang':
      return new Promise<never>(() => undefined);
  }
}

/** The account id the platform writes in the history for the shopper's own changes. */
export const OWNER_ID = '3f2b8c0e-5d41-4a39-9c1e-0a7f6b2d4e11';
export const OPERATOR_ID = '8c5d2e7a-41b6-4f90-a3d8-6e1f0b9c7a52';

export type SeedOrder = {
  readonly id: string;
  readonly createdAt: string;
  readonly orderStatus?: Order['orderStatus'];
  readonly paymentStatus?: Order['paymentStatus'];
  readonly cancellationReason?: Order['cancellationReason'];
  readonly paymentAttemptId?: string | null;
  readonly statusHistory?: Order['statusHistory'];
};

/** An order the platform already holds (placed, paid, one Rake line unless a test says otherwise). */
export function seedOrder(input: SeedOrder): Order {
  const paymentStatus = input.paymentStatus ?? 'approved';
  const orderStatus = input.orderStatus ?? 'placed';
  const created = Date.parse(input.createdAt);
  const at = (seconds: number): string => new Date(created + seconds * 1000).toISOString();
  const order: Order = {
    id: input.id,
    orderStatus,
    paymentStatus,
    cancellationReason: input.cancellationReason ?? null,
    lines: [
      {
        productId: '9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01',
        name: 'Rake',
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
    statusHistory: input.statusHistory ?? [
      { kind: 'order', status: 'placed', at: at(0), by: OWNER_ID },
      { kind: 'payment', status: 'pending', at: at(0), by: 'system' },
      ...(paymentStatus === 'pending'
        ? []
        : [{ kind: 'payment' as const, status: paymentStatus, at: at(1), by: 'system' }]),
    ],
    createdAt: input.createdAt,
    paymentAttemptId: input.paymentAttemptId ?? null,
    paymentExpiresAt:
      paymentStatus === 'pending' ? new Date(created + PAYMENT_WINDOW_MS).toISOString() : null,
  };
  orderServer.orders.set(order.id, order);
  return order;
}

export const orderHandlers = [
  http.get(ORDERS_URL, ({ request }) => {
    const url = new URL(request.url);
    const page = Number(url.searchParams.get('page') ?? '0');
    const size = Number(url.searchParams.get('size') ?? '20');
    const all = [...orderServer.orders.values()].sort(
      (left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt),
    );
    return HttpResponse.json({
      items: all.slice(page * size, page * size + size),
      page,
      size,
      totalItems: all.length,
    });
  }),
  http.post(`${ORDERS_URL}/:orderId/cancellation`, ({ params }) => {
    const id = String(params['orderId']);
    orderServer.cancellations.push(id);
    const order = orderServer.orders.get(id);
    if (order === undefined) return problem('not-found', 404, 'Not found', 'Order not found.');
    if (order.orderStatus !== 'placed') {
      return problem(
        'order-not-cancellable',
        409,
        'Order cannot be cancelled',
        `Shoppers can only cancel an order while it is placed; this order is ${order.orderStatus}.`,
      );
    }
    const cancelled: Order = {
      ...order,
      orderStatus: 'cancelled',
      cancellationReason: 'SHOPPER_REQUEST',
      statusHistory: [
        ...order.statusHistory,
        {
          kind: 'order',
          status: 'cancelled',
          at: orderServer.now().toISOString(),
          by: OWNER_ID,
        },
      ],
    };
    orderServer.orders.set(id, cancelled);
    return HttpResponse.json(cancelled);
  }),
  http.post(ORDERS_URL, async ({ request }) => {
    const key = request.headers.get('Idempotency-Key');
    const body = (await request.json()) as { paymentMethod: { token: string } };
    orderServer.placeRequests.push({ key, body });
    const scripted = orderServer.script.shift();
    if (scripted !== undefined) return refuse(scripted);
    if (key !== null) {
      const replay = orderServer.replays.get(key);
      if (replay !== undefined) return HttpResponse.json(replay.order, { status: replay.status });
    }
    const order = orderFromCart(body.paymentMethod.token, orderServer.now());
    orderServer.orders.set(order.id, order);
    const status = order.paymentStatus === 'pending' ? 202 : 201;
    if (key !== null) orderServer.replays.set(key, { status, order });
    if (status === 201) {
      cartServer.lines = [];
      cartServer.revision += 1;
    }
    return HttpResponse.json(order, { status });
  }),
  http.get(`${ORDERS_URL}/:orderId`, ({ params }) => {
    const id = String(params['orderId']);
    orderServer.reads.push(id);
    const order = orderServer.orders.get(id);
    if (order === undefined) return problem('not-found', 404, 'Not found', 'Order not found.');
    return HttpResponse.json(order);
  }),
];
