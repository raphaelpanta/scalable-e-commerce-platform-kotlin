import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import type { Order } from '@app/order/orderPort';
import {
  actorOf,
  awaitingPayment,
  orderNumber,
  paymentDeadline,
  pollInterval,
  toOrderView,
} from '@app/order/orderView';
import {
  allowedActions,
  CANCELLATION_REASONS,
  type CancellationReason,
  ORDER_STATUSES,
  type OrderStatus,
  PAYMENT_STATUSES,
  type PaymentStatus,
} from '@domain/status';

// Property tests of OrderView (data-model.md §3.3): the invariants of the feature 004 rules hold
// for every order the platform can answer, and a view never shows more than its rules allow.
const instant = fc.date({
  min: new Date('2020-01-01T00:00:00Z'),
  max: new Date('2040-01-01T00:00:00Z'),
  noInvalidDate: true,
});
const accountId = fc.uuid();
const reason = fc.constantFrom(...CANCELLATION_REASONS);
const money = fc.record({
  amountMinor: fc.integer({ min: 0, max: 10_000_000 }),
  currency: fc.constantFrom('BRL', 'EUR', 'USD'),
});

type Statuses = {
  readonly orderStatus: OrderStatus;
  readonly paymentStatus: PaymentStatus;
};

/** The pairs feature 004 can produce: preparation onwards needs an approved payment. */
const consistentStatuses: fc.Arbitrary<Statuses> = fc
  .tuple(fc.constantFrom(...ORDER_STATUSES), fc.constantFrom(...PAYMENT_STATUSES))
  .map(([orderStatus, paymentStatus]) => ({
    orderStatus,
    paymentStatus:
      orderStatus === 'preparing' || orderStatus === 'shipped' || orderStatus === 'delivered'
        ? 'approved'
        : paymentStatus,
  }));

function wireOrder(
  statuses: Statuses,
  owner: string,
  other: string,
  createdAt: Date,
  cancellation: CancellationReason,
  extra: Partial<Order> = {},
): Order {
  const at = (offsetSeconds: number): string =>
    new Date(createdAt.getTime() + offsetSeconds * 1000).toISOString();
  return {
    id: '0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10',
    orderStatus: statuses.orderStatus,
    paymentStatus: statuses.paymentStatus,
    cancellationReason: statuses.orderStatus === 'cancelled' ? cancellation : null,
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
    statusHistory: [
      { kind: 'order', status: 'placed', at: at(0), by: owner },
      { kind: 'payment', status: 'pending', at: at(0), by: 'system' },
      { kind: 'order', status: statuses.orderStatus, at: at(60), by: other },
    ],
    createdAt: at(0),
    paymentAttemptId: null,
    paymentExpiresAt: statuses.paymentStatus === 'pending' ? at(1800) : null,
    ...extra,
  };
}

const anOrder = fc
  .tuple(consistentStatuses, accountId, accountId, instant, reason)
  .map(([statuses, owner, other, createdAt, cancellation]) =>
    wireOrder(statuses, owner, other, createdAt, cancellation),
  );

describe('OrderView invariants (data-model.md §3.3)', () => {
  it('has a cancellation reason if and only if the order is cancelled', () => {
    fc.assert(
      fc.property(anOrder, (order) => {
        const view = toOrderView(order);
        expect(view.cancellationReason !== undefined).toBe(order.orderStatus === 'cancelled');
        expect(view.cancellationReason).toBe(
          order.orderStatus === 'cancelled' ? order.cancellationReason : undefined,
        );
      }),
    );
  });

  it('shows an approved payment whenever the order is preparing, shipped or delivered', () => {
    fc.assert(
      fc.property(anOrder, (order) => {
        const view = toOrderView(order);
        if (['preparing', 'shipped', 'delivered'].includes(view.orderStatus)) {
          expect(view.paymentStatus).toBe('approved');
        }
        expect(view.orderStatus).toBe(order.orderStatus);
        expect(view.paymentStatus).toBe(order.paymentStatus);
      }),
    );
  });

  it('has a payment deadline if and only if the payment is pending, and it is the platform one', () => {
    fc.assert(
      fc.property(anOrder, (order) => {
        const view = toOrderView(order);
        expect(view.paymentDeadline !== undefined).toBe(order.paymentStatus === 'pending');
        if (order.paymentStatus === 'pending') {
          expect(view.paymentDeadline).toEqual(new Date(order.paymentExpiresAt ?? ''));
          expect(view.paymentDeadline).toEqual(paymentDeadline(order));
        }
      }),
    );
  });

  it('never shows more than its rules allow, whatever the wire data carries', () => {
    fc.assert(
      fc.property(anOrder, reason, instant, (order, anyReason, anyInstant) => {
        const noisy: Order = {
          ...order,
          cancellationReason: anyReason,
          paymentExpiresAt: anyInstant.toISOString(),
        };
        const view = toOrderView(noisy);
        expect(view.cancellationReason !== undefined).toBe(noisy.orderStatus === 'cancelled');
        expect(view.paymentDeadline !== undefined).toBe(noisy.paymentStatus === 'pending');
      }),
    );
  });

  it('carries the platform lines, total, address and number unchanged', () => {
    fc.assert(
      fc.property(anOrder, fc.uuid(), money, (order, id, total) => {
        const view = toOrderView({ ...order, id, total });
        expect(view.id).toBe(id);
        expect(view.number).toBe(orderNumber(id));
        expect(view.total).toEqual(total);
        expect(view.lines).toEqual(order.lines);
        expect(view.deliveryAddress).toEqual(order.deliveryAddress);
        expect(view.createdAt).toEqual(new Date(order.createdAt));
      }),
    );
  });
});

describe('OrderView actions for a shopper', () => {
  it('offers cancel only while the order is placed, and nothing else', () => {
    fc.assert(
      fc.property(anOrder, (order) => {
        const { actions } = toOrderView(order);
        expect(actions).toEqual(allowedActions('shopper', order.orderStatus, order.paymentStatus));
        expect(actions).toEqual(order.orderStatus === 'placed' ? [{ kind: 'cancel' }] : []);
      }),
    );
  });

  it('never offers an advance to a shopper', () => {
    fc.assert(
      fc.property(anOrder, (order) => {
        expect(toOrderView(order).actions.some((action) => action.kind === 'advance')).toBe(false);
      }),
    );
  });
});

describe('OrderView history actors (never an account id)', () => {
  it('shows the placing shopper as "you", the platform as "system" and anyone else as "operator"', () => {
    fc.assert(
      fc.property(
        fc.uuid(),
        fc.uuid(),
        fc.constantFrom(...CANCELLATION_REASONS),
        (owner, other, anyReason) => {
          fc.pre(owner !== other);
          const order = wireOrder(
            { orderStatus: 'cancelled', paymentStatus: 'approved' },
            owner,
            other,
            new Date('2026-10-02T10:00:00Z'),
            anyReason,
          );
          const view = toOrderView(order);
          expect(view.history.map((entry) => entry.actor)).toEqual(['you', 'system', 'operator']);
          expect(JSON.stringify(view.history)).not.toContain(owner);
          expect(JSON.stringify(view.history)).not.toContain(other);
        },
      ),
    );
  });

  it('labels every actor with one of three words for any history', () => {
    fc.assert(
      fc.property(
        fc.array(fc.oneof(fc.uuid(), fc.constant('system')), { minLength: 1, maxLength: 8 }),
        (actors) => {
          const base = wireOrder(
            { orderStatus: 'placed', paymentStatus: 'approved' },
            actors[0] ?? 'system',
            'system',
            new Date('2026-10-02T10:00:00Z'),
            'OPERATOR',
          );
          const order: Order = {
            ...base,
            statusHistory: actors.map((by, index) => ({
              kind: 'order',
              status: 'placed',
              at: new Date(Date.UTC(2026, 9, 2, 10, index)).toISOString(),
              by,
            })),
          };
          for (const entry of toOrderView(order).history) {
            expect(['you', 'operator', 'system']).toContain(entry.actor);
          }
        },
      ),
    );
  });

  it('decides the actor from the owner id, with "system" always the platform', () => {
    expect(actorOf('system', 'system')).toBe('system');
    expect(actorOf('system', 'a1')).toBe('system');
    expect(actorOf('a1', 'a1')).toBe('you');
    expect(actorOf('a2', 'a1')).toBe('operator');
    expect(actorOf('a1', undefined)).toBe('operator');
  });

  it('orders the history by time, oldest first, keeping the platform order for equal instants', () => {
    fc.assert(
      fc.property(fc.array(instant, { minLength: 1, maxLength: 8 }), (instants) => {
        const base = wireOrder(
          { orderStatus: 'placed', paymentStatus: 'approved' },
          'owner',
          'system',
          new Date('2026-10-02T10:00:00Z'),
          'OPERATOR',
        );
        const order: Order = {
          ...base,
          statusHistory: instants.map((at, index) => ({
            kind: 'payment',
            status: index % 2 === 0 ? 'pending' : 'approved',
            at: at.toISOString(),
            by: 'system',
          })),
        };
        const times = toOrderView(order).history.map((entry) => entry.at.getTime());
        expect(times).toEqual([...times].sort((left, right) => left - right));
        expect(times).toHaveLength(instants.length);
      }),
    );
    const base = wireOrder(
      { orderStatus: 'placed', paymentStatus: 'approved' },
      'owner',
      'system',
      new Date('2026-10-02T10:00:00Z'),
      'OPERATOR',
    );
    const tied = toOrderView({
      ...base,
      statusHistory: [
        { kind: 'order', status: 'placed', at: '2026-10-02T10:00:00Z', by: 'owner' },
        { kind: 'payment', status: 'pending', at: '2026-10-02T10:00:00Z', by: 'system' },
        { kind: 'payment', status: 'approved', at: '2026-10-02T10:00:00Z', by: 'system' },
      ],
    });
    expect(tied.history.map((entry) => `${entry.kind}:${entry.status}`)).toEqual([
      'order:placed',
      'payment:pending',
      'payment:approved',
    ]);
  });
});

describe('polling (FR-009)', () => {
  const deadlineOffset = fc.integer({ min: -3_600_000, max: 3_600_000 });

  it('stops on a final status and past the deadline, runs every 5 s before it while pending', () => {
    fc.assert(
      fc.property(anOrder, deadlineOffset, (order, offsetMs) => {
        const deadline =
          typeof order.paymentExpiresAt === 'string' ? new Date(order.paymentExpiresAt) : undefined;
        const now = new Date((deadline ?? new Date(order.createdAt)).getTime() + offsetMs);
        const polling = pollInterval(order, now);
        if (order.paymentStatus !== 'pending') {
          expect(polling).toBe(false);
          expect(awaitingPayment(order, now)).toBe(false);
        } else {
          expect(polling).toBe(offsetMs < 0 ? 5_000 : false);
        }
      }),
    );
  });

  it('stops for every order whose payment is final, however far the deadline is', () => {
    fc.assert(
      fc.property(anOrder, fc.constantFrom<PaymentStatus>('approved', 'failed'), (order, final) => {
        const finished: Order = {
          ...order,
          paymentStatus: final,
          paymentExpiresAt: '2999-01-01T00:00:00Z',
        };
        expect(pollInterval(finished, new Date('2026-10-02T10:00:00Z'))).toBe(false);
      }),
    );
  });
});
