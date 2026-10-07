import { CartRevision } from '@domain/cartRevision';
import type { IdempotencyKey } from '@domain/ids';
import { PaymentMethod, type PaymentMethodId } from '@domain/paymentMethods';

import type {
  ChangedLine,
  Order,
  PlaceOrderRequest,
  PlaceOrderResult,
} from '../order/orderPort.ts';

// CheckoutDraft and its state machine (data-model.md §3.2). The draft holds ids and the key only
// (it is what sessionStorage persists); the status, the refusal being shown and the confirmed
// order are runtime state. Pure: the idempotency key is minted by the caller and handed in with
// the event, so the rule "one key per distinct request body" is decided here without randomness.
export const CHECKOUT_STEPS = ['address', 'payment', 'review'] as const;
export type CheckoutStep = (typeof CHECKOUT_STEPS)[number];

export type CheckoutDraft = {
  readonly step: CheckoutStep;
  /** The id of a saved address (a new address is saved first, then referenced). */
  readonly addressId?: string | undefined;
  readonly paymentMethodId?: PaymentMethodId | undefined;
  /** The revision the shopper last saw or explicitly accepted; sent as `cartRevision`. */
  readonly acknowledgedRevision?: CartRevision | undefined;
  /** Minted for the current body; reused for byte-identical retries, cleared when the body changes. */
  readonly idempotencyKey?: IdempotencyKey | undefined;
};

export const EMPTY_DRAFT: CheckoutDraft = Object.freeze({ step: 'address' } as const);

export const CHECKOUT_STATUSES = [
  'reviewing',
  'submitted',
  'confirmed',
  'refusedPriceChange',
  'acknowledged',
  'refused',
] as const;
export type CheckoutStatus = (typeof CHECKOUT_STATUSES)[number];

export type PriceChange = {
  readonly changedLines: readonly ChangedLine[];
  readonly currentCartRevision: string;
};

/** A refusal other than a price change: shown until the shopper acts. */
export type Refusal = Exclude<
  PlaceOrderResult,
  { kind: 'placed' } | { kind: 'priceChanged' } | { kind: 'unauthorized' }
>;

/** The key minted for a body: the same body gets it back, even after a change and a change back. */
export type MintedKey = {
  readonly key: IdempotencyKey;
  readonly request: PlaceOrderRequest;
};

export type CheckoutState = {
  readonly draft: CheckoutDraft;
  readonly status: CheckoutStatus;
  /** Runtime only (never persisted): which body the draft's key was minted for. */
  readonly minted: MintedKey | undefined;
  /** True between `submitted` and the platform's answer; blocks a second submission. */
  readonly inFlight: boolean;
  /** True after a network failure while submitted: the shopper retries with the same key. */
  readonly interrupted: boolean;
  readonly priceChange: PriceChange | undefined;
  readonly refusal: Refusal | undefined;
  readonly order: Order | undefined;
};

export type CheckoutEvent =
  | { readonly type: 'stepShown'; readonly step: CheckoutStep }
  | { readonly type: 'addressChosen'; readonly addressId: string }
  | { readonly type: 'paymentMethodChosen'; readonly paymentMethodId: PaymentMethodId }
  /** The cart as displayed on the review step; its revision is the one the shopper sees. */
  | { readonly type: 'cartSeen'; readonly revision: CartRevision }
  /** The shopper confirms; `fresh` is used only when the draft holds no key for this body. */
  | { readonly type: 'submitted'; readonly fresh: IdempotencyKey }
  | { readonly type: 'placed'; readonly order: Order }
  | { readonly type: 'priceChanged'; readonly change: PriceChange }
  /** Explicit acceptance of the new prices: the current revision and a new key. */
  | { readonly type: 'pricesAccepted'; readonly fresh: IdempotencyKey }
  | { readonly type: 'refused'; readonly refusal: Refusal }
  /** The shopper adjusts the cart or the draft after a refusal. */
  | { readonly type: 'adjusted' }
  /** Network error or timeout while submitted: no automatic retry. */
  | { readonly type: 'interrupted' }
  /** 401 while submitting: back to reviewing with the draft kept. */
  | { readonly type: 'unauthorized' };

export function initialCheckoutState(draft: CheckoutDraft = EMPTY_DRAFT): CheckoutState {
  return {
    draft,
    status: 'reviewing',
    minted: undefined,
    inFlight: false,
    interrupted: false,
    priceChange: undefined,
    refusal: undefined,
    order: undefined,
  };
}

/** The place-order body of a draft, or `undefined` while a part is missing. */
export function requestOf(draft: CheckoutDraft): PlaceOrderRequest | undefined {
  if (
    draft.addressId === undefined ||
    draft.paymentMethodId === undefined ||
    draft.acknowledgedRevision === undefined
  ) {
    return undefined;
  }
  return {
    addressId: draft.addressId,
    cartRevision: draft.acknowledgedRevision.value,
    paymentMethod: PaymentMethod.toRequest(draft.paymentMethodId),
  };
}

/** The key a submission of `draft` must carry: the one minted for this body, else `fresh`. */
export function keyFor(draft: CheckoutDraft, fresh: IdempotencyKey): IdempotencyKey {
  return draft.idempotencyKey ?? fresh;
}

function sameRequest(
  left: PlaceOrderRequest | undefined,
  right: PlaceOrderRequest | undefined,
): boolean {
  if (left === undefined || right === undefined) return left === right;
  return (
    left.addressId === right.addressId &&
    left.cartRevision === right.cartRevision &&
    left.paymentMethod.token === right.paymentMethod.token
  );
}

/**
 * The state with `changes` applied to the draft. The key follows the body: unchanged body, same
 * key; the body the key was minted for, that key again; any other body, no key until minted.
 */
function changed(state: CheckoutState, changes: Partial<CheckoutDraft>): CheckoutState {
  const next = { ...state.draft, ...changes };
  const request = requestOf(next);
  let idempotencyKey: IdempotencyKey | undefined = undefined;
  if (sameRequest(request, requestOf(state.draft))) idempotencyKey = state.draft.idempotencyKey;
  else if (state.minted !== undefined && sameRequest(request, state.minted.request)) {
    idempotencyKey = state.minted.key;
  }
  return { ...state, draft: { ...next, idempotencyKey } };
}

const SUBMITTABLE: ReadonlySet<CheckoutStatus> = new Set<CheckoutStatus>([
  'reviewing',
  'acknowledged',
  'refused',
  'submitted',
]);

export function reduceCheckout(state: CheckoutState, event: CheckoutEvent): CheckoutState {
  if (state.status === 'confirmed') return state;
  switch (event.type) {
    case 'stepShown':
      return { ...state, draft: { ...state.draft, step: event.step } };
    case 'addressChosen':
      return changed(state, { addressId: event.addressId });
    case 'paymentMethodChosen':
      return changed(state, { paymentMethodId: event.paymentMethodId });
    case 'cartSeen':
      // While a price change waits for acceptance, the acknowledged revision is the acceptance's.
      if (state.status === 'refusedPriceChange' || state.inFlight) return state;
      return changed(state, { acknowledgedRevision: event.revision });
    case 'submitted': {
      if (!SUBMITTABLE.has(state.status) || state.inFlight) return state;
      const request = requestOf(state.draft);
      if (request === undefined) return state;
      const key = keyFor(state.draft, event.fresh);
      return {
        ...state,
        draft: { ...state.draft, idempotencyKey: key },
        minted: { key, request },
        status: 'submitted',
        inFlight: true,
        interrupted: false,
        refusal: undefined,
        priceChange: undefined,
      };
    }
    case 'placed':
      if (state.status !== 'submitted') return state;
      return { ...state, status: 'confirmed', inFlight: false, order: event.order };
    case 'priceChanged':
      if (state.status !== 'submitted') return state;
      return { ...state, status: 'refusedPriceChange', inFlight: false, priceChange: event.change };
    case 'pricesAccepted': {
      if (state.status !== 'refusedPriceChange' || state.priceChange === undefined) return state;
      const revision = CartRevision.parse(state.priceChange.currentCartRevision);
      if (!revision.ok) return state;
      const draft = {
        ...state.draft,
        acknowledgedRevision: revision.value,
        idempotencyKey: event.fresh,
      };
      const request = requestOf(draft);
      return {
        ...state,
        status: 'acknowledged',
        priceChange: undefined,
        draft,
        minted: request === undefined ? state.minted : { key: event.fresh, request },
      };
    }
    case 'refused':
      if (state.status !== 'submitted') return state;
      return { ...state, status: 'refused', inFlight: false, refusal: event.refusal };
    case 'adjusted':
      if (state.status !== 'refused') return state;
      return { ...state, status: 'reviewing', refusal: undefined };
    case 'interrupted':
      if (state.status !== 'submitted') return state;
      return { ...state, inFlight: false, interrupted: true };
    case 'unauthorized':
      return {
        ...state,
        status: 'reviewing',
        inFlight: false,
        interrupted: false,
        priceChange: undefined,
        refusal: undefined,
      };
  }
}
