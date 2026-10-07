import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  CHECKOUT_STATUSES,
  CHECKOUT_STEPS,
  type CheckoutDraft,
  type CheckoutEvent,
  type CheckoutState,
  type CheckoutStatus,
  EMPTY_DRAFT,
  initialCheckoutState,
  keyFor,
  reduceCheckout,
  requestOf,
} from '@app/checkout/checkoutDraft';
import {
  clearDraft,
  DRAFT_MEMBERS,
  DRAFT_STORAGE_KEY,
  type DraftStorage,
  loadDraft,
  parseDraft,
  saveDraft,
  serializeDraft,
} from '@app/checkout/draftStorage';
import type { Order } from '@app/order/orderPort';
import { CartRevision } from '@domain/cartRevision';
import { IdempotencyKey } from '@domain/ids';
import {
  PAYMENT_METHOD_IDS,
  PAYMENT_METHODS,
  PAYMENT_METHODS_SCOPE,
  PaymentMethod,
} from '@domain/paymentMethods';

const uuid = fc.uuid({ version: 4 }).map((u) => u.toLowerCase());
const key = uuid.map((u) => {
  const parsed = IdempotencyKey.parse(u);
  if (!parsed.ok) throw new Error('fixture key');
  return parsed.value;
});
const revision = fc.stringMatching(/^rev-[a-f0-9]{4,8}$/).map((r) => {
  const parsed = CartRevision.parse(r);
  if (!parsed.ok) throw new Error('fixture revision');
  return parsed.value;
});
const methodId = fc.constantFrom(...PAYMENT_METHOD_IDS);
const step = fc.constantFrom(...CHECKOUT_STEPS);

const order: Order = {
  id: '0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10',
  orderStatus: 'placed',
  paymentStatus: 'approved',
  lines: [],
  total: { amountMinor: 1, currency: 'BRL' },
  deliveryAddress: { recipientName: 'A', line1: 'L', city: 'C', postalCode: 'P', country: 'BR' },
  statusHistory: [],
  createdAt: '2026-10-02T10:15:00Z',
};

const completeDraft: fc.Arbitrary<CheckoutDraft> = fc.record({
  step,
  addressId: uuid,
  paymentMethodId: methodId,
  acknowledgedRevision: revision,
  idempotencyKey: fc.option(key, { nil: undefined }),
});

const event: fc.Arbitrary<CheckoutEvent> = fc.oneof(
  step.map((s): CheckoutEvent => ({ type: 'stepShown', step: s })),
  uuid.map((addressId): CheckoutEvent => ({ type: 'addressChosen', addressId })),
  methodId.map((paymentMethodId): CheckoutEvent => ({
    type: 'paymentMethodChosen',
    paymentMethodId,
  })),
  revision.map((r): CheckoutEvent => ({ type: 'cartSeen', revision: r })),
  key.map((fresh): CheckoutEvent => ({ type: 'submitted', fresh })),
  fc.constant<CheckoutEvent>({ type: 'placed', order }),
  revision.map((r): CheckoutEvent => ({
    type: 'priceChanged',
    change: { changedLines: [], currentCartRevision: r.value },
  })),
  key.map((fresh): CheckoutEvent => ({ type: 'pricesAccepted', fresh })),
  fc.constant<CheckoutEvent>({
    type: 'refused',
    refusal: { kind: 'insufficientStock', unavailableLines: [] },
  }),
  fc.constant<CheckoutEvent>({ type: 'adjusted' }),
  fc.constant<CheckoutEvent>({ type: 'interrupted' }),
  fc.constant<CheckoutEvent>({ type: 'unauthorized' }),
);

/** The transition table of data-model.md §3.2: the statuses an event may lead to from `from`. */
function allowedTargets(from: CheckoutStatus, e: CheckoutEvent): readonly CheckoutStatus[] {
  switch (e.type) {
    case 'submitted':
      return ['reviewing', 'acknowledged', 'refused', 'submitted'].includes(from)
        ? [from, 'submitted']
        : [from];
    case 'placed':
      return from === 'submitted' ? ['confirmed'] : [from];
    case 'priceChanged':
      return from === 'submitted' ? ['refusedPriceChange'] : [from];
    case 'pricesAccepted':
      return from === 'refusedPriceChange' ? ['acknowledged'] : [from];
    case 'refused':
      return from === 'submitted' ? ['refused'] : [from];
    case 'adjusted':
      return from === 'refused' ? ['reviewing'] : [from];
    case 'unauthorized':
      return ['reviewing'];
    default:
      return [from];
  }
}

function run(events: readonly CheckoutEvent[], start = initialCheckoutState()): CheckoutState {
  return events.reduce(reduceCheckout, start);
}

describe('CheckoutDraft state machine (data-model.md §3.2)', () => {
  it('starts reviewing with the restored draft and no key, refusal or order', () => {
    fc.assert(
      fc.property(completeDraft, (draft) => {
        const state = initialCheckoutState(draft);
        expect(state.status).toBe('reviewing');
        expect(state.draft).toBe(draft);
        expect(state.inFlight).toBe(false);
        expect(state.order).toBeUndefined();
        expect(state.refusal).toBeUndefined();
      }),
    );
    expect(initialCheckoutState().draft).toBe(EMPTY_DRAFT);
  });

  it('follows the transition table for every sequence of events, and confirmed is terminal', () => {
    fc.assert(
      fc.property(completeDraft, fc.array(event, { maxLength: 25 }), (draft, events) => {
        let state = initialCheckoutState(draft);
        for (const e of events) {
          const next = reduceCheckout(state, e);
          expect(CHECKOUT_STATUSES).toContain(next.status);
          if (state.status === 'confirmed') expect(next).toBe(state);
          else expect(allowedTargets(state.status, e)).toContain(next.status);
          expect(next.inFlight).toBe(next.status === 'submitted' && !next.interrupted);
          if (next.status === 'confirmed') expect(next.order).toBe(order);
          if (next.status === 'refusedPriceChange') expect(next.priceChange).toBeDefined();
          if (next.status === 'refused') expect(next.refusal).toBeDefined();
          if (next.status === 'reviewing') expect(next.refusal).toBeUndefined();
          state = next;
        }
      }),
    );
  });

  it('walks the happy path: reviewing → submitted → confirmed, deleting nothing but the status', () => {
    fc.assert(
      fc.property(completeDraft, key, (draft, fresh) => {
        const submitted = run([{ type: 'submitted', fresh }], initialCheckoutState(draft));
        expect(submitted.status).toBe('submitted');
        expect(submitted.inFlight).toBe(true);
        expect(submitted.draft.idempotencyKey).toEqual(draft.idempotencyKey ?? fresh);
        const confirmed = reduceCheckout(submitted, { type: 'placed', order });
        expect(confirmed.status).toBe('confirmed');
        expect(confirmed.order).toBe(order);
        expect(confirmed.draft).toEqual(submitted.draft);
      }),
    );
  });

  it('refuses to submit an incomplete draft or while a submission is in flight (double click)', () => {
    fc.assert(
      fc.property(completeDraft, key, key, (draft, first, second) => {
        for (const missing of ['addressId', 'paymentMethodId', 'acknowledgedRevision'] as const) {
          const partial = { ...draft, [missing]: undefined };
          expect(requestOf(partial)).toBeUndefined();
          const state = reduceCheckout(initialCheckoutState(partial), {
            type: 'submitted',
            fresh: first,
          });
          expect(state.status).toBe('reviewing');
        }
        const once = run([{ type: 'submitted', fresh: first }], initialCheckoutState(draft));
        const twice = reduceCheckout(once, { type: 'submitted', fresh: second });
        expect(twice).toBe(once);
      }),
    );
  });

  it('price change → explicit acceptance → acknowledged with the current revision and a new key → submitted', () => {
    fc.assert(
      fc.property(completeDraft, key, key, revision, (draft, first, second, current) => {
        const refused = run(
          [
            { type: 'submitted', fresh: first },
            {
              type: 'priceChanged',
              change: { changedLines: [], currentCartRevision: current.value },
            },
          ],
          initialCheckoutState(draft),
        );
        expect(refused.status).toBe('refusedPriceChange');
        // Seeing the cart again while the acceptance is pending changes nothing.
        expect(reduceCheckout(refused, { type: 'cartSeen', revision: current })).toBe(refused);
        // Submitting again without accepting is refused.
        expect(reduceCheckout(refused, { type: 'submitted', fresh: second })).toBe(refused);
        const accepted = reduceCheckout(refused, { type: 'pricesAccepted', fresh: second });
        expect(accepted.status).toBe('acknowledged');
        expect(accepted.draft.acknowledgedRevision).toEqual(current);
        expect(accepted.draft.idempotencyKey).toBe(second);
        expect(accepted.priceChange).toBeUndefined();
        const resubmitted = reduceCheckout(accepted, { type: 'submitted', fresh: first });
        expect(resubmitted.status).toBe('submitted');
        expect(resubmitted.draft.idempotencyKey).toBe(second);
        expect(requestOf(resubmitted.draft)?.cartRevision).toBe(current.value);
      }),
    );
  });

  it('a refusal returns to reviewing when the shopper adjusts; a 401 returns to reviewing from anywhere with the draft kept', () => {
    fc.assert(
      fc.property(
        completeDraft,
        key,
        fc.array(event, { maxLength: 10 }),
        (draft, fresh, events) => {
          const refused = run(
            [
              { type: 'submitted', fresh },
              {
                type: 'refused',
                refusal: {
                  kind: 'paymentDeclined',
                  declineReason: 'card_rejected',
                  orderId: undefined,
                },
              },
            ],
            initialCheckoutState(draft),
          );
          expect(refused.status).toBe('refused');
          expect(refused.refusal?.kind).toBe('paymentDeclined');
          const adjusted = reduceCheckout(refused, { type: 'adjusted' });
          expect(adjusted.status).toBe('reviewing');
          expect(adjusted.refusal).toBeUndefined();

          const somewhere = run(events, initialCheckoutState(draft));
          const back = reduceCheckout(somewhere, { type: 'unauthorized' });
          if (somewhere.status !== 'confirmed') {
            expect(back.status).toBe('reviewing');
            expect(back.draft).toEqual(somewhere.draft);
            expect(back.inFlight).toBe(false);
          }
        },
      ),
    );
  });

  it('a network failure keeps the submission and its key for a manual retry, never an automatic one', () => {
    fc.assert(
      fc.property(completeDraft, key, key, (draft, first, second) => {
        const interrupted = run(
          [{ type: 'submitted', fresh: first }, { type: 'interrupted' }],
          initialCheckoutState(draft),
        );
        expect(interrupted.status).toBe('submitted');
        expect(interrupted.inFlight).toBe(false);
        expect(interrupted.interrupted).toBe(true);
        const retried = reduceCheckout(interrupted, { type: 'submitted', fresh: second });
        expect(retried.inFlight).toBe(true);
        expect(retried.interrupted).toBe(false);
        expect(retried.draft.idempotencyKey).toEqual(draft.idempotencyKey ?? first);
      }),
    );
  });
});

describe('idempotency rule: one key per distinct request body', () => {
  type Edit = CheckoutEvent & {
    type: 'addressChosen' | 'paymentMethodChosen' | 'cartSeen' | 'stepShown';
  };
  const edit: fc.Arbitrary<Edit> = fc.oneof(
    uuid.map((addressId): Edit => ({ type: 'addressChosen', addressId })),
    methodId.map((paymentMethodId): Edit => ({ type: 'paymentMethodChosen', paymentMethodId })),
    revision.map((r): Edit => ({ type: 'cartSeen', revision: r })),
    step.map((s): Edit => ({ type: 'stepShown', step: s })),
  );

  it('reuses the key for a byte-identical body and regenerates it when the body changed', () => {
    fc.assert(
      fc.property(
        completeDraft,
        fc.array(fc.tuple(fc.array(edit, { maxLength: 4 }), revision), {
          minLength: 1,
          maxLength: 8,
        }),
        (draft, rounds) => {
          let state = initialCheckoutState({ ...draft, idempotencyKey: undefined });
          let previous: { body: string; key: string } | undefined;
          // Every minted key is distinct, as `crypto.randomUUID` guarantees in practice.
          let minted = 0;
          const mint = () => {
            minted += 1;
            const parsed = IdempotencyKey.parse(
              `00000000-0000-4000-8000-${String(minted).padStart(12, '0')}`,
            );
            if (!parsed.ok) throw new Error('fixture key');
            return parsed.value;
          };
          for (const [edits, current] of rounds) {
            // Between submissions the shopper edits the draft (an interrupted or refused state lets her).
            state = reduceCheckout(state, { type: 'interrupted' });
            state = run(edits, state);
            const request = requestOf(state.draft);
            if (request === undefined) continue;
            state = reduceCheckout(state, { type: 'submitted', fresh: mint() });
            if (state.status !== 'submitted' || !state.inFlight) continue;
            const body = JSON.stringify(request);
            const used = state.draft.idempotencyKey!.value;
            if (previous !== undefined) {
              expect(used === previous.key).toBe(body === previous.body);
            }
            previous = { body, key: used };
            // The platform answers a price change: acceptance always mints a new key.
            state = reduceCheckout(state, {
              type: 'priceChanged',
              change: { changedLines: [], currentCartRevision: current.value },
            });
            const fresh = mint();
            state = reduceCheckout(state, { type: 'pricesAccepted', fresh });
            expect(state.draft.idempotencyKey).toBe(fresh);
            previous = {
              body: JSON.stringify(requestOf(state.draft)),
              key: state.draft.idempotencyKey!.value,
            };
          }
        },
      ),
    );
  });

  it('keyFor prefers the draft key and keeps the fresh one otherwise', () => {
    fc.assert(
      fc.property(completeDraft, key, (draft, fresh) => {
        expect(keyFor(draft, fresh)).toBe(draft.idempotencyKey ?? fresh);
      }),
    );
  });

  it('an edit that leaves the body unchanged keeps the key; a different value drops it', () => {
    fc.assert(
      fc.property(
        completeDraft.filter((d) => d.idempotencyKey !== undefined),
        uuid,
        methodId,
        revision,
        (draft, address, method, r) => {
          const state = initialCheckoutState(draft);
          expect(
            reduceCheckout(state, { type: 'addressChosen', addressId: draft.addressId! }).draft
              .idempotencyKey,
          ).toBe(draft.idempotencyKey);
          expect(
            reduceCheckout(state, {
              type: 'paymentMethodChosen',
              paymentMethodId: draft.paymentMethodId!,
            }).draft.idempotencyKey,
          ).toBe(draft.idempotencyKey);
          expect(
            reduceCheckout(state, { type: 'cartSeen', revision: draft.acknowledgedRevision! }).draft
              .idempotencyKey,
          ).toBe(draft.idempotencyKey);
          expect(
            reduceCheckout(state, { type: 'stepShown', step: 'review' }).draft.idempotencyKey,
          ).toBe(draft.idempotencyKey);
          expect(
            reduceCheckout(state, { type: 'addressChosen', addressId: address }).draft
              .idempotencyKey,
          ).toBe(address === draft.addressId ? draft.idempotencyKey : undefined);
          expect(
            reduceCheckout(state, { type: 'paymentMethodChosen', paymentMethodId: method }).draft
              .idempotencyKey,
          ).toBe(method === draft.paymentMethodId ? draft.idempotencyKey : undefined);
          expect(
            reduceCheckout(state, { type: 'cartSeen', revision: r }).draft.idempotencyKey,
          ).toBe(
            CartRevision.equals(r, draft.acknowledgedRevision!) ? draft.idempotencyKey : undefined,
          );
        },
      ),
    );
  });
});

describe('sessionStorage persistence of the draft', () => {
  function memoryStorage(
    initial: Record<string, string> = {},
  ): DraftStorage & { readonly data: Map<string, string> } {
    const data = new Map(Object.entries(initial));
    return {
      data,
      getItem: (k) => data.get(k) ?? null,
      setItem: (k, v) => {
        data.set(k, v);
      },
      removeItem: (k) => {
        data.delete(k);
      },
    };
  }

  const partialDraft: fc.Arbitrary<CheckoutDraft> = fc.record({
    step,
    addressId: fc.option(uuid, { nil: undefined }),
    paymentMethodId: fc.option(methodId, { nil: undefined }),
    acknowledgedRevision: fc.option(revision, { nil: undefined }),
    idempotencyKey: fc.option(key, { nil: undefined }),
  });

  it('round-trips any draft and stores only the five allowed members', () => {
    fc.assert(
      fc.property(partialDraft, (draft) => {
        const stored = serializeDraft(draft);
        const members = Object.keys(JSON.parse(stored) as Record<string, unknown>);
        for (const member of members) expect(DRAFT_MEMBERS).toContain(member);
        expect(parseDraft(stored)).toEqual(
          Object.fromEntries(Object.entries(draft).filter(([, v]) => v !== undefined)),
        );
        const storage = memoryStorage();
        saveDraft(storage, draft);
        expect(storage.data.get(DRAFT_STORAGE_KEY)).toBe(stored);
        expect(loadDraft(storage)).toEqual(parseDraft(stored));
        clearDraft(storage);
        expect(loadDraft(storage)).toBe(EMPTY_DRAFT);
      }),
    );
  });

  it('never serialises an address text, an email, a card number or a token, whatever is attached to the draft', () => {
    fc.assert(
      fc.property(
        partialDraft,
        fc.dictionary(
          fc.constantFrom(
            'email',
            'line1',
            'recipientName',
            'cardNumber',
            'token',
            'accessToken',
            'password',
          ),
          fc.string({ minLength: 1 }).map((value) => `leaked:${value}`),
        ),
        (draft, leaked) => {
          const stored = serializeDraft({ ...leaked, ...draft });
          const parsed = JSON.parse(stored) as Record<string, unknown>;
          for (const forbidden of Object.keys(leaked)) expect(parsed).not.toHaveProperty(forbidden);
          expect(stored.includes('leaked:')).toBe(false);
        },
      ),
    );
  });

  it('drops stored members that are not valid value objects and ignores unreadable storage', () => {
    fc.assert(
      fc.property(fc.anything(), (junk) => {
        const serialised: unknown = JSON.stringify(junk);
        const raw =
          typeof junk === 'string'
            ? junk
            : typeof serialised === 'string'
              ? serialised
              : 'undefined';
        const parsed = parseDraft(raw);
        expect(CHECKOUT_STEPS).toContain(parsed.step);
        if (parsed.addressId !== undefined) expect(parsed.addressId).toMatch(/^[0-9a-f-]{36}$/i);
        if (parsed.paymentMethodId !== undefined)
          expect(PaymentMethod.isId(parsed.paymentMethodId)).toBe(true);
        if (parsed.idempotencyKey !== undefined)
          expect(IdempotencyKey.parse(parsed.idempotencyKey.value).ok).toBe(true);
      }),
    );
    expect(parseDraft(null)).toBe(EMPTY_DRAFT);
    expect(parseDraft('{not json')).toBe(EMPTY_DRAFT);
    expect(
      parseDraft(
        JSON.stringify({
          step: 'teleport',
          addressId: 'not-a-uuid',
          paymentMethodId: 'tok_real_card',
          acknowledgedRevision: '',
          idempotencyKey: 'KEY',
          email: 'ana@example.com',
        }),
      ),
    ).toEqual({ step: 'address' });
    const broken: DraftStorage = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('quota');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    };
    expect(loadDraft(broken)).toBe(EMPTY_DRAFT);
    expect(() => {
      saveDraft(broken, EMPTY_DRAFT);
      clearDraft(broken);
    }).not.toThrow();
  });
});

describe('payment methods (FR-006): the seeded simulator tokens, labelled for local development', () => {
  it('lists exactly the three seeded tokens, each with a label and a description, no card fields', () => {
    expect(PAYMENT_METHODS.map((m) => m.id)).toEqual([
      'tok_sim_approve_4242',
      'tok_sim_decline_0001',
      'tok_sim_unreachable',
    ]);
    expect([...PAYMENT_METHOD_IDS]).toEqual(PAYMENT_METHODS.map((m) => m.id));
    for (const method of PAYMENT_METHODS) {
      expect(method.label.length).toBeGreaterThan(0);
      expect(method.description.length).toBeGreaterThan(0);
      expect(Object.keys(method).sort()).toEqual(['description', 'id', 'label']);
      expect(PaymentMethod.byId(method.id)).toBe(method);
      expect(PaymentMethod.toRequest(method.id)).toEqual({ type: 'card', token: method.id });
    }
    expect(PAYMENT_METHODS_SCOPE).toMatch(/local development/i);
  });

  it('accepts only the seeded ids', () => {
    fc.assert(
      fc.property(fc.string(), (candidate) => {
        expect(PaymentMethod.isId(candidate)).toBe(
          (PAYMENT_METHOD_IDS as readonly string[]).includes(candidate),
        );
      }),
    );
    expect(() => PaymentMethod.byId('tok_sim_other' as never)).toThrow('unknown payment method');
  });
});
