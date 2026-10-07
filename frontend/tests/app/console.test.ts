import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { ProblemError } from '@api/problem';
import { DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE } from '@app/catalog/browseParams';
import {
  actorOf,
  type ConsoleOrderFilter,
  consoleActions,
  filterFromSearch,
  listParams,
  searchFromFilter,
  targetOf,
  withStatus,
} from '@app/console/consoleOrders';
import {
  parseDelta,
  REASON_MAX,
  reasonOf,
  StockAdjustment,
  stockAdjustmentRequest,
} from '@app/console/stockAdjustment';
import { ORDER_STATUSES, PAYMENT_STATUSES } from '@domain/status';
import { describeFailure, sanitizeAttributes } from '@telemetry/policy';

import { POLICY } from '../telemetry/support.ts';

const orderStatus = fc.constantFrom(...ORDER_STATUSES);
const paymentStatus = fc.constantFrom(...PAYMENT_STATUSES);

describe('console order actions (data-model.md §3.3, operator)', () => {
  it('offers cancel while placed or preparing and never once the order is terminal', () => {
    fc.assert(
      fc.property(orderStatus, paymentStatus, (status, payment) => {
        const cancel = consoleActions({ orderStatus: status, paymentStatus: payment }).some(
          (action) => action.kind === 'cancel',
        );
        expect(cancel).toBe(status === 'placed' || status === 'preparing');
      }),
    );
  });

  it('advances to preparing only with an approved payment', () => {
    fc.assert(
      fc.property(paymentStatus, (payment) => {
        const advance = consoleActions({ orderStatus: 'placed', paymentStatus: payment }).filter(
          (action) => action.kind === 'advance',
        );
        expect(advance).toEqual(
          payment === 'approved' ? [{ kind: 'advance', to: 'preparing' }] : [],
        );
      }),
    );
  });

  it('advances preparing to shipped and shipped to delivered whatever the payment status says', () => {
    fc.assert(
      fc.property(paymentStatus, (payment) => {
        expect(consoleActions({ orderStatus: 'preparing', paymentStatus: payment })).toContainEqual(
          { kind: 'advance', to: 'shipped' },
        );
        expect(consoleActions({ orderStatus: 'shipped', paymentStatus: payment })).toEqual([
          { kind: 'advance', to: 'delivered' },
        ]);
      }),
    );
  });

  it('offers nothing for delivered and cancelled orders', () => {
    fc.assert(
      fc.property(fc.constantFrom('delivered', 'cancelled'), paymentStatus, (status, payment) => {
        expect(consoleActions({ orderStatus: status, paymentStatus: payment })).toEqual([]);
      }),
    );
  });

  it('maps an action to the status the platform is asked for', () => {
    expect(targetOf({ kind: 'cancel' })).toBe('cancelled');
    expect(targetOf({ kind: 'advance', to: 'preparing' })).toBe('preparing');
    expect(targetOf({ kind: 'advance', to: 'shipped' })).toBe('shipped');
    expect(targetOf({ kind: 'advance', to: 'delivered' })).toBe('delivered');
  });

  it('names the actor of a history entry without exposing an account id', () => {
    const open = { cancellationReason: null };
    expect(actorOf({ kind: 'payment', status: 'approved', by: 'system' }, open)).toBe('System');
    expect(actorOf({ kind: 'order', status: 'placed', by: '7c1d4f3e' }, open)).toBe('Shopper');
    for (const status of ['preparing', 'shipped', 'delivered'] as const) {
      expect(actorOf({ kind: 'order', status, by: 'e5a1c3b7' }, open)).toBe('Operator');
    }
    expect(
      actorOf(
        { kind: 'order', status: 'cancelled', by: 'e5a1c3b7' },
        { cancellationReason: 'OPERATOR' },
      ),
    ).toBe('Operator');
    expect(
      actorOf(
        { kind: 'order', status: 'cancelled', by: '7c1d4f3e' },
        { cancellationReason: 'SHOPPER_REQUEST' },
      ),
    ).toBe('Shopper');
    expect(
      actorOf(
        { kind: 'order', status: 'cancelled', by: 'system' },
        { cancellationReason: 'PAYMENT_EXPIRED' },
      ),
    ).toBe('System');
    fc.assert(
      fc.property(fc.uuid(), (account) => {
        expect(
          actorOf({ kind: 'order', status: 'shipped', by: account }, open).includes(account),
        ).toBe(false);
      }),
    );
  });
});

const filters: fc.Arbitrary<ConsoleOrderFilter> = fc.record(
  {
    status: orderStatus,
    page: fc.integer({ min: 1, max: 10_000 }),
    size: fc.integer({ min: 1, max: MAX_PAGE_SIZE }).filter((size) => size !== DEFAULT_PAGE_SIZE),
  },
  { requiredKeys: [] },
);

describe('ConsoleOrderFilter in the URL (FR-003)', () => {
  it('round-trips status, page and size through the query string', () => {
    fc.assert(
      fc.property(filters, (filter) => {
        expect(filterFromSearch(searchFromFilter(filter))).toEqual(filter);
      }),
    );
  });

  it('reads size 1..100 from the URL and ignores the default, zero and anything above 100', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: MAX_PAGE_SIZE }), (size) => {
        const parsed = filterFromSearch(new URLSearchParams({ size: String(size) }));
        expect(parsed.size).toBe(size === DEFAULT_PAGE_SIZE ? undefined : size);
      }),
    );
    for (const size of ['0', '101', '-1', '1.5', 'many', '']) {
      expect(filterFromSearch(new URLSearchParams({ size })).size).toBeUndefined();
    }
  });

  it('reads page as a zero-based index and ignores anything else', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 100_000 }), (page) => {
        expect(filterFromSearch(new URLSearchParams({ page: String(page) })).page).toBe(page);
      }),
    );
    for (const page of ['-1', '1.5', 'next', '']) {
      expect(filterFromSearch(new URLSearchParams({ page })).page).toBeUndefined();
    }
  });

  it('accepts exactly the five order statuses for ?status=', () => {
    fc.assert(
      fc.property(fc.string(), (candidate) => {
        const parsed = filterFromSearch(new URLSearchParams({ status: candidate })).status;
        expect(parsed).toBe(
          (ORDER_STATUSES as readonly string[]).includes(candidate) ? candidate : undefined,
        );
      }),
    );
  });

  it('asks the platform for the status through the orderStatus parameter, not by filtering', () => {
    fc.assert(
      fc.property(filters, (filter) => {
        const params = listParams(filter);
        expect(params.orderStatus).toBe(filter.status);
        expect(params.page).toBe(filter.page);
        expect(params.size).toBe(filter.size);
        expect(
          Object.keys(params).every((key) => ['orderStatus', 'page', 'size'].includes(key)),
        ).toBe(true);
      }),
    );
  });

  it('changing the status goes back to the first page and keeps the page size', () => {
    fc.assert(
      fc.property(filters, fc.option(orderStatus, { nil: undefined }), (filter, status) => {
        const next = filterFromSearch(withStatus(searchFromFilter(filter), status));
        expect(next.status).toBe(status);
        expect(next.page).toBeUndefined();
        expect(next.size).toBe(filter.size);
      }),
    );
  });
});

describe('StockAdjustment (data-model.md §3.4)', () => {
  const nonZero = fc.integer({ min: -2_000_000, max: 2_000_000 }).filter((delta) => delta !== 0);

  it('accepts any non-zero integer, with an optional sign and surrounding spaces', () => {
    fc.assert(
      fc.property(nonZero, fc.constantFrom('', ' ', '  '), (delta, padding) => {
        const text = `${padding}${delta > 0 && padding === ' ' ? '+' : ''}${String(delta)}${padding}`;
        expect(parseDelta(text)).toEqual({ ok: true, value: delta });
      }),
    );
  });

  it('refuses zero in every spelling', () => {
    for (const text of ['0', '-0', '+0', '000', ' 0 ']) {
      expect(parseDelta(text)).toEqual({ ok: false, reason: 'zero' });
    }
  });

  it('refuses text that is not an integer, and an empty delta as required', () => {
    expect(parseDelta('')).toEqual({ ok: false, reason: 'required' });
    expect(parseDelta('   ')).toEqual({ ok: false, reason: 'required' });
    for (const text of ['1.5', 'abc', '1e3', '0x10', '--1', '1 2', '9007199254740993']) {
      expect(parseDelta(text)).toEqual({ ok: false, reason: 'not-an-integer' });
    }
  });

  it('requires a reason of 1..255 characters once trimmed and sends it trimmed', () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1, maxLength: REASON_MAX }), (typed) => {
        const reason = reasonOf(typed);
        const trimmed = typed.trim();
        if (trimmed.length === 0) expect(reason).toEqual({ ok: false, reason: 'required' });
        else expect(reason).toEqual({ ok: true, value: trimmed });
      }),
    );
    expect(reasonOf('')).toEqual({ ok: false, reason: 'required' });
    expect(reasonOf('x'.repeat(REASON_MAX))).toEqual({ ok: true, value: 'x'.repeat(REASON_MAX) });
    expect(reasonOf('x'.repeat(REASON_MAX + 1))).toEqual({ ok: false, reason: 'too-long' });
  });

  it('builds the request from valid input and reports every invalid field otherwise', () => {
    expect(StockAdjustment.parse({ delta: '-2', reason: ' Damaged units ' })).toEqual({
      ok: true,
      value: { delta: -2, reason: 'Damaged units' },
    });
    expect(StockAdjustment.parse({ delta: '0', reason: '' })).toEqual({
      ok: false,
      reason: [
        { field: 'delta', reason: 'zero' },
        { field: 'reason', reason: 'required' },
      ],
    });
    expect(stockAdjustmentRequest({ delta: 5, reason: 'Recount' })).toEqual({
      delta: 5,
      reason: 'Recount',
    });
  });

  it('never lets the reason reach a telemetry attribute', () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1, maxLength: REASON_MAX }), (typed) => {
        const reason = `reason-marker ${typed}`;
        const failure = new ProblemError({
          type: 'validation',
          title: reason,
          detail: reason,
          status: 422,
          errors: [{ field: 'reason', message: reason }],
          extensions: { reason },
        });
        expect(JSON.stringify(describeFailure(failure))).not.toContain('reason-marker');
        expect(
          sanitizeAttributes(
            { 'stock.reason': reason, 'stock.delta': 5, 'element.id': 'stock-reason' },
            POLICY,
          ),
        ).toEqual({ 'element.id': 'stock-reason' });
      }),
    );
  });
});
