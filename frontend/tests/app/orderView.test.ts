import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  awaitingPayment,
  formatRemaining,
  ORDER_NUMBER_LENGTH,
  ORDER_POLL_INTERVAL_MS,
  orderNumber,
  paymentDeadline,
  pollInterval,
  remainingUntil,
} from '@app/order/orderView';
import { PAYMENT_STATUSES } from '@domain/status';

const instant = fc.date({
  min: new Date('2000-01-01T00:00:00Z'),
  max: new Date('2100-01-01T00:00:00Z'),
  noInvalidDate: true,
});
const paymentStatus = fc.constantFrom(...PAYMENT_STATUSES);

describe('order number', () => {
  it('is the first eight characters of the id (the full id stays copyable)', () => {
    expect(ORDER_NUMBER_LENGTH).toBe(8);
    fc.assert(
      fc.property(fc.uuid(), (id) => {
        expect(orderNumber(id)).toBe(id.slice(0, 8));
        expect(id.startsWith(orderNumber(id))).toBe(true);
      }),
    );
  });
});

describe('payment deadline from paymentExpiresAt (FR-009)', () => {
  it('is present exactly while the payment is pending with a parsable instant', () => {
    fc.assert(
      fc.property(paymentStatus, fc.option(instant, { nil: null }), (status, expiresAt) => {
        const order = { paymentStatus: status, paymentExpiresAt: expiresAt?.toISOString() ?? null };
        const deadline = paymentDeadline(order);
        if (status === 'pending' && expiresAt !== null) expect(deadline).toEqual(expiresAt);
        else expect(deadline).toBeUndefined();
      }),
    );
    expect(paymentDeadline({ paymentStatus: 'pending' })).toBeUndefined();
    expect(paymentDeadline({ paymentStatus: 'pending', paymentExpiresAt: 'soon' })).toBeUndefined();
  });

  it('awaits payment, and polls every 5 s, strictly before the deadline only', () => {
    expect(ORDER_POLL_INTERVAL_MS).toBe(5_000);
    fc.assert(
      fc.property(
        paymentStatus,
        instant,
        fc.integer({ min: -3_600_000, max: 3_600_000 }),
        (status, deadline, offsetMs) => {
          const order = { paymentStatus: status, paymentExpiresAt: deadline.toISOString() };
          const now = new Date(deadline.getTime() + offsetMs);
          const expected = status === 'pending' && offsetMs < 0;
          expect(awaitingPayment(order, now)).toBe(expected);
          expect(pollInterval(order, now)).toBe(expected ? 5_000 : false);
        },
      ),
    );
    expect(pollInterval(null, new Date())).toBe(false);
    expect(pollInterval(undefined, new Date())).toBe(false);
    const deadline = new Date('2026-10-02T10:45:00Z');
    const order = { paymentStatus: 'pending' as const, paymentExpiresAt: deadline.toISOString() };
    expect(awaitingPayment(order, new Date(deadline.getTime() - 1))).toBe(true);
    expect(awaitingPayment(order, deadline)).toBe(false);
  });

  it('counts the remaining whole seconds down to zero and never below', () => {
    fc.assert(
      fc.property(
        instant,
        fc.integer({ min: -10_000_000, max: 10_000_000 }),
        (deadline, offsetMs) => {
          const now = new Date(deadline.getTime() - offsetMs);
          const { minutes, seconds } = remainingUntil(deadline, now);
          const total = Math.max(0, Math.ceil(offsetMs / 1000));
          expect(minutes * 60 + seconds).toBe(total);
          expect(seconds).toBeGreaterThanOrEqual(0);
          expect(seconds).toBeLessThan(60);
          expect(formatRemaining({ minutes, seconds })).toBe(
            `${String(minutes)}:${String(seconds).padStart(2, '0')}`,
          );
        },
      ),
    );
    const deadline = new Date('2026-10-02T10:45:00Z');
    expect(remainingUntil(deadline, new Date('2026-10-02T10:15:00Z'))).toEqual({
      minutes: 30,
      seconds: 0,
    });
    expect(remainingUntil(deadline, new Date('2026-10-02T10:44:59.500Z'))).toEqual({
      minutes: 0,
      seconds: 1,
    });
    expect(remainingUntil(deadline, new Date('2026-10-02T11:00:00Z'))).toEqual({
      minutes: 0,
      seconds: 0,
    });
    expect(formatRemaining({ minutes: 29, seconds: 5 })).toBe('29:05');
  });
});
