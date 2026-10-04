import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  allowedActions,
  cancelAllowed,
  ORDER_STATUSES,
  OrderStatus,
  PAYMENT_STATUSES,
  PaymentStatus,
  ROLES,
} from '@domain/status';

const orderStatus = fc.constantFrom(...ORDER_STATUSES);
const paymentStatus = fc.constantFrom(...PAYMENT_STATUSES);
const role = fc.constantFrom(...ROLES);

describe('OrderStatus transitions (data-model.md §2.1)', () => {
  it('follows the feature 004 table exactly', () => {
    expect(OrderStatus.allowedNext('placed')).toEqual(['preparing', 'cancelled']);
    expect(OrderStatus.allowedNext('preparing')).toEqual(['shipped', 'cancelled']);
    expect(OrderStatus.allowedNext('shipped')).toEqual(['delivered']);
    expect(OrderStatus.allowedNext('delivered')).toEqual([]);
    expect(OrderStatus.allowedNext('cancelled')).toEqual([]);
  });

  it('terminal statuses are exactly delivered and cancelled and allow nothing', () => {
    fc.assert(
      fc.property(orderStatus, (status) => {
        const terminal = status === 'delivered' || status === 'cancelled';
        expect(OrderStatus.isTerminal(status)).toBe(terminal);
        expect(OrderStatus.allowedNext(status).length === 0).toBe(terminal);
      }),
    );
  });

  it('never allows a transition back to placed or to itself', () => {
    fc.assert(
      fc.property(orderStatus, (status) => {
        const next = OrderStatus.allowedNext(status);
        expect(next).not.toContain('placed');
        expect(next).not.toContain(status);
      }),
    );
  });

  it('recognises the status strings', () => {
    fc.assert(
      fc.property(fc.string(), (candidate) => {
        expect(OrderStatus.isOrderStatus(candidate)).toBe(
          (ORDER_STATUSES as readonly string[]).includes(candidate),
        );
      }),
    );
  });
});

describe('PaymentStatus transitions', () => {
  it('pending goes to approved or failed; both are terminal', () => {
    expect(PaymentStatus.allowedNext('pending')).toEqual(['approved', 'failed']);
    fc.assert(
      fc.property(paymentStatus, (status) => {
        expect(PaymentStatus.isTerminal(status)).toBe(status !== 'pending');
        expect(PaymentStatus.allowedNext(status)).not.toContain('pending');
        expect(PaymentStatus.isPaymentStatus(status)).toBe(true);
      }),
    );
  });
});

describe('cancelAllowed(role, status)', () => {
  it('shopper only while placed; operator while placed or preparing', () => {
    fc.assert(
      fc.property(role, orderStatus, (r, status) => {
        const expected = status === 'placed' || (r === 'operator' && status === 'preparing');
        expect(cancelAllowed(r, status)).toBe(expected);
      }),
    );
  });
});

describe('allowedActions(role, orderStatus, paymentStatus)', () => {
  it('is empty for terminal statuses', () => {
    fc.assert(
      fc.property(role, fc.constantFrom('delivered', 'cancelled'), paymentStatus, (r, s, p) => {
        expect(allowedActions(r, s, p)).toEqual([]);
      }),
    );
  });

  it('offers cancel exactly when cancelAllowed says so', () => {
    fc.assert(
      fc.property(role, orderStatus, paymentStatus, (r, s, p) => {
        const hasCancel = allowedActions(r, s, p).some((a) => a.kind === 'cancel');
        expect(hasCancel).toBe(cancelAllowed(r, s));
      }),
    );
  });

  it('never offers advance to a shopper', () => {
    fc.assert(
      fc.property(orderStatus, paymentStatus, (s, p) => {
        expect(allowedActions('shopper', s, p).some((a) => a.kind === 'advance')).toBe(false);
      }),
    );
  });

  it('advance(preparing) requires payment approved; shipped and delivered follow the chain', () => {
    fc.assert(
      fc.property(paymentStatus, (p) => {
        const fromPlaced = allowedActions('operator', 'placed', p).filter(
          (a) => a.kind === 'advance',
        );
        expect(fromPlaced).toEqual(p === 'approved' ? [{ kind: 'advance', to: 'preparing' }] : []);
        expect(allowedActions('operator', 'preparing', p)).toContainEqual({
          kind: 'advance',
          to: 'shipped',
        });
        expect(allowedActions('operator', 'shipped', p)).toEqual([
          { kind: 'advance', to: 'delivered' },
        ]);
      }),
    );
  });

  it('every offered advance target is an allowed next status', () => {
    fc.assert(
      fc.property(role, orderStatus, paymentStatus, (r, s, p) => {
        for (const action of allowedActions(r, s, p)) {
          if (action.kind === 'advance') expect(OrderStatus.allowedNext(s)).toContain(action.to);
        }
      }),
    );
  });
});
