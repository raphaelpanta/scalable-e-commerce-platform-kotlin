import { QueryClient } from '@tanstack/react-query';
import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import type { Cart, CartLine, CartPort, MergeResult } from '@app/cart/cartPort';
import {
  CART_KEY,
  createCartActions,
  MERGE_NOTICE_KEY,
  optimisticQuantity,
  UNAVAILABLE_KEY,
} from '@app/cart/cartStore';
import { EMPTY_CART_VIEW, itemCount, toCartView } from '@app/cart/cartView';
import { type LineQuantityCommand, Quantity, QUANTITY_MAX, QUANTITY_MIN } from '@domain/quantity';

const uuid = fc.uuid({ version: 4 });
const money = fc.record({
  amountMinor: fc.nat({ max: 10_000_000 }),
  currency: fc.constant('BRL'),
});
const line: fc.Arbitrary<CartLine> = fc.record({
  id: uuid,
  productId: uuid,
  productName: fc.string({ minLength: 1, maxLength: 40 }),
  quantity: fc.integer({ min: QUANTITY_MIN, max: QUANTITY_MAX }),
  priceAtAdd: money,
  currentPrice: money,
  priceChanged: fc.boolean(),
  lineTotal: money,
});
const cart: fc.Arbitrary<Cart> = fc.record({
  id: uuid,
  revision: fc.string({ minLength: 1, maxLength: 20 }),
  lines: fc.uniqueArray(line, { maxLength: 6, selector: (l) => l.id }),
  total: money,
  updatedAt: fc.constant('2026-10-02T10:20:00Z'),
});
const nonEmptyCart = cart.filter((c) => c.lines.length > 0);
const quantity = fc.integer({ min: QUANTITY_MIN, max: QUANTITY_MAX }).map((n) => {
  const parsed = Quantity.parse(n);
  if (!parsed.ok) throw new Error('fixture');
  return parsed.value;
});
const command: fc.Arbitrary<LineQuantityCommand> = fc.oneof(quantity, fc.constant(Quantity.zero));

type FakePort = CartPort & { readonly calls: string[]; readonly updates: unknown[] };

function fakePort(answer: Cart, options: { refuse?: boolean; merge?: MergeResult | null } = {}) {
  const port: FakePort = {
    calls: [],
    updates: [],
    getCart: () => {
      port.calls.push('get');
      return Promise.resolve(answer);
    },
    addLine: () => {
      port.calls.push('add');
      return Promise.resolve(answer);
    },
    setLineQuantity: (lineId, cmd) => {
      port.calls.push('set');
      port.updates.push({ lineId, quantity: cmd.value });
      return options.refuse === true
        ? Promise.reject(new Error('422 insufficient stock'))
        : Promise.resolve(answer);
    },
    removeLine: () => {
      port.calls.push('remove');
      return Promise.resolve(answer);
    },
    clearCart: () => {
      port.calls.push('clear');
      return Promise.resolve();
    },
    mergeCart: () => {
      port.calls.push('merge');
      return Promise.resolve(options.merge ?? null);
    },
  };
  return port;
}

const client = () => new QueryClient({ defaultOptions: { queries: { retry: false } } });

describe('CartView derivation (data-model.md §3.2)', () => {
  it('mirrors the server cart: amounts untouched, item count is the sum of quantities, empty iff no lines', () => {
    fc.assert(
      fc.property(cart, (c) => {
        const view = toCartView(c);
        expect(view.id).toBe(c.id);
        expect(view.revision?.value).toBe(c.revision);
        expect(view.total).toBe(c.total);
        expect(view.lines.map((l) => l.lineTotal)).toEqual(c.lines.map((l) => l.lineTotal));
        expect(view.lines.map((l) => l.currentPrice)).toEqual(c.lines.map((l) => l.currentPrice));
        expect(view.lines.map((l) => l.priceAtAdd)).toEqual(c.lines.map((l) => l.priceAtAdd));
        expect(view.lines.map((l) => l.priceChanged)).toEqual(c.lines.map((l) => l.priceChanged));
        expect(view.itemCount).toBe(c.lines.reduce((sum, l) => sum + l.quantity, 0));
        expect(view.isEmpty).toBe(c.lines.length === 0);
        expect(view.canCheckout).toBe(c.lines.length > 0);
        expect(view.updatedAt).toEqual(new Date('2026-10-02T10:20:00Z'));
        expect(view.mergeNotice).toBeUndefined();
      }),
    );
    expect(itemCount([])).toBe(0);
  });

  it('is the empty view when the platform knows no cart', () => {
    expect(toCartView(null)).toEqual(EMPTY_CART_VIEW);
    expect(toCartView(null).canCheckout).toBe(false);
    expect(toCartView(null).total).toBeUndefined();
  });

  it('flags unavailable lines from the products the storefront learnt about and blocks checkout', () => {
    fc.assert(
      fc.property(nonEmptyCart, fc.array(fc.nat({ max: 5 })), (c, picks) => {
        const flagged = new Set(
          picks.map((index) => c.lines[index % c.lines.length]?.productId ?? ''),
        );
        const view = toCartView(c, { unavailableProductIds: flagged });
        for (const l of view.lines) expect(l.unavailable).toBe(flagged.has(l.productId));
        expect(view.canCheckout).toBe(view.lines.every((l) => !l.unavailable));
      }),
    );
  });

  it('carries the merge notice only when a line was capped', () => {
    fc.assert(
      fc.property(
        cart,
        fc.array(
          fc.record({
            productId: uuid,
            requestedQuantity: fc.integer({ min: 1, max: 200 }),
            appliedQuantity: fc.integer({ min: 0, max: 99 }),
          }),
          { maxLength: 3 },
        ),
        (c, capped) => {
          const view = toCartView(c, { mergeNotice: capped });
          expect(view.mergeNotice).toEqual(capped.length === 0 ? undefined : capped);
        },
      ),
    );
  });

  it('never computes a total: the view exposes the server amounts only', () => {
    const view = toCartView({
      id: '8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34',
      revision: 'rev-1',
      lines: [
        {
          id: 'c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18',
          productId: '0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21',
          productName: 'Rake',
          quantity: 3,
          priceAtAdd: { amountMinor: 1000, currency: 'BRL' },
          currentPrice: { amountMinor: 1200, currency: 'BRL' },
          priceChanged: true,
          lineTotal: { amountMinor: 3600, currency: 'BRL' },
        },
      ],
      // Deliberately not 3 × 1200: the view must show what the server says.
      total: { amountMinor: 999, currency: 'BRL' },
      updatedAt: 'not-a-date',
    });
    expect(view.total).toEqual({ amountMinor: 999, currency: 'BRL' });
    expect(view.updatedAt).toBeUndefined();
    expect(view.itemCount).toBe(3);
  });
});

describe('optimistic quantity (rolled back on 422)', () => {
  it('replaces the quantity of the line, removes it for Quantity.zero and leaves every amount alone', () => {
    fc.assert(
      fc.property(nonEmptyCart, fc.nat({ max: 5 }), command, (c, index, cmd) => {
        const target = c.lines[index % c.lines.length]!;
        const next = optimisticQuantity(c, target.id, cmd);
        expect(next.total).toBe(c.total);
        expect(next.revision).toBe(c.revision);
        if (Quantity.isRemoval(cmd)) {
          expect(next.lines.map((l) => l.id)).toEqual(
            c.lines.filter((l) => l.id !== target.id).map((l) => l.id),
          );
        } else {
          expect(next.lines).toHaveLength(c.lines.length);
          for (const l of next.lines) {
            const before = c.lines.find((b) => b.id === l.id)!;
            expect(l.quantity).toBe(l.id === target.id ? cmd.value : before.quantity);
            expect(l.lineTotal).toBe(before.lineTotal);
          }
        }
      }),
    );
  });

  it('changes nothing for an unknown line', () => {
    fc.assert(
      fc.property(cart, uuid, command, (c, lineId, cmd) => {
        fc.pre(!c.lines.some((l) => l.id === lineId));
        expect(optimisticQuantity(c, lineId, cmd)).toBe(c);
      }),
    );
  });

  it('shows the new quantity at once and restores the previous cart when the platform refuses', async () => {
    await fc.assert(
      fc.asyncProperty(
        nonEmptyCart,
        nonEmptyCart,
        fc.nat({ max: 5 }),
        command,
        async (before, after, index, cmd) => {
          const target = before.lines[index % before.lines.length]!;
          const queryClient = client();
          queryClient.setQueryData<Cart | null>(CART_KEY, before);

          const refusing = fakePort(after, { refuse: true });
          const rollback = createCartActions(queryClient, refusing).setQuantity(target.id, cmd);
          expect(queryClient.getQueryData<Cart>(CART_KEY)).toEqual(
            optimisticQuantity(before, target.id, cmd),
          );
          await expect(rollback).rejects.toThrow('422');
          expect(queryClient.getQueryData<Cart>(CART_KEY)).toEqual(before);
          expect(refusing.updates).toEqual([{ lineId: target.id, quantity: cmd.value }]);

          const accepting = fakePort(after);
          await createCartActions(queryClient, accepting).setQuantity(target.id, cmd);
          expect(queryClient.getQueryData<Cart>(CART_KEY)).toEqual(after);
          expect(queryClient.getQueryData<readonly string[]>(UNAVAILABLE_KEY)).toEqual([]);
        },
      ),
    );
  });

  it('sends Quantity.zero as the remove command (quantity 0)', async () => {
    const queryClient = client();
    const port = fakePort({
      id: 'x',
      revision: 'r',
      lines: [],
      total: { amountMinor: 0, currency: 'BRL' },
      updatedAt: '2026-10-02T10:20:00Z',
    });
    await createCartActions(queryClient, port).setQuantity('line-1', Quantity.zero);
    expect(port.updates).toEqual([{ lineId: 'line-1', quantity: 0 }]);
    expect(port.calls).toEqual(['set']);
  });
});

describe('cart actions over the query cache', () => {
  const empty: Cart = {
    id: 'c',
    revision: 'r',
    lines: [],
    total: { amountMinor: 0, currency: 'BRL' },
    updatedAt: '2026-10-02T10:20:00Z',
  };

  it('replaces the cached cart with the server answer on add and remove', async () => {
    await fc.assert(
      fc.asyncProperty(cart, quantity, async (answer, q) => {
        const queryClient = client();
        const port = fakePort(answer);
        const actions = createCartActions(queryClient, port);
        expect(await actions.add('p', q)).toBe(answer);
        expect(queryClient.getQueryData<Cart>(CART_KEY)).toBe(answer);
        queryClient.setQueryData<Cart | null>(CART_KEY, null);
        expect(await actions.remove('l')).toBe(answer);
        expect(queryClient.getQueryData<Cart>(CART_KEY)).toBe(answer);
        expect(port.calls).toEqual(['add', 'remove']);
      }),
    );
  });

  it('clear empties through the platform and invalidates the cached cart', async () => {
    const queryClient = client();
    const port = fakePort(empty);
    queryClient.setQueryData<Cart | null>(CART_KEY, empty);
    await createCartActions(queryClient, port).clear();
    expect(port.calls).toEqual(['clear']);
    expect(queryClient.getQueryState(CART_KEY)?.isInvalidated).toBe(true);
  });

  it('merge stores the account cart and the capped lines as the notice, dismissable', async () => {
    await fc.assert(
      fc.asyncProperty(
        cart,
        fc.array(
          fc.record({
            productId: uuid,
            requestedQuantity: fc.integer({ min: 1, max: 200 }),
            appliedQuantity: fc.integer({ min: 0, max: 99 }),
          }),
          { maxLength: 3 },
        ),
        async (merged, cappedLines) => {
          const queryClient = client();
          const port = fakePort(empty, { merge: { cart: merged, cappedLines } });
          const actions = createCartActions(queryClient, port);
          expect(await actions.merge()).toEqual(cappedLines);
          expect(queryClient.getQueryData<Cart>(CART_KEY)).toBe(merged);
          expect(queryClient.getQueryData(MERGE_NOTICE_KEY)).toEqual(cappedLines);
          actions.dismissMergeNotice();
          expect(queryClient.getQueryData(MERGE_NOTICE_KEY)).toEqual([]);
        },
      ),
    );
  });

  it('merge with nothing to merge invalidates the cart and reports no notice', async () => {
    const queryClient = client();
    queryClient.setQueryData<Cart | null>(CART_KEY, empty);
    const actions = createCartActions(queryClient, fakePort(empty, { merge: null }));
    expect(await actions.merge()).toBeNull();
    expect(queryClient.getQueryState(CART_KEY)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryData(MERGE_NOTICE_KEY)).toBeUndefined();
  });

  it('flags the products a refused checkout named, deduplicated, until the next server cart', async () => {
    const queryClient = client();
    const actions = createCartActions(queryClient, fakePort(empty));
    actions.flagUnavailable(['p1', 'p2', 'p1']);
    expect(queryClient.getQueryData(UNAVAILABLE_KEY)).toEqual(['p1', 'p2']);
    await actions.remove('l');
    expect(queryClient.getQueryData(UNAVAILABLE_KEY)).toEqual([]);
  });
});
