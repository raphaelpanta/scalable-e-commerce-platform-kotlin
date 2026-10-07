import { useCallback, useEffect, useMemo, useReducer, useRef } from 'react';

import type { CartRevision } from '@domain/cartRevision';
import { IdempotencyKey, type UuidSource } from '@domain/ids';
import type { PaymentMethodId } from '@domain/paymentMethods';

import type { Order, PlaceOrderResult } from '../order/orderPort.ts';
import { usePorts } from '../ports.ts';
import {
  type CheckoutState,
  type CheckoutStep,
  initialCheckoutState,
  keyFor,
  reduceCheckout,
  type Refusal,
  requestOf,
} from './checkoutDraft.ts';
import { clearDraft, type DraftStorage, loadDraft, saveDraft } from './draftStorage.ts';

export type SubmitOutcome =
  | { readonly kind: 'placed'; readonly order: Order }
  | { readonly kind: 'priceChanged' }
  | { readonly kind: 'refused'; readonly refusal: Refusal }
  | { readonly kind: 'unauthorized' }
  /** Network failure or outage: nothing is retried automatically; the key is kept for a manual retry. */
  | { readonly kind: 'interrupted'; readonly error: unknown }
  /** A part of the draft is missing, or a submission is already in flight. */
  | { readonly kind: 'notSent' };

export type CheckoutHandle = {
  readonly state: CheckoutState;
  readonly showStep: (step: CheckoutStep) => void;
  readonly chooseAddress: (addressId: string) => void;
  readonly choosePaymentMethod: (paymentMethodId: PaymentMethodId) => void;
  readonly seeCart: (revision: CartRevision) => void;
  readonly acceptPrices: () => void;
  readonly adjust: () => void;
  readonly submit: () => Promise<SubmitOutcome>;
};

export type UseCheckoutOptions = {
  readonly storage?: DraftStorage;
  readonly uuid?: UuidSource;
};

function mint(source: UuidSource): IdempotencyKey {
  const key = IdempotencyKey.generate(source);
  if (!key.ok) throw new Error('the UUID source did not return a canonical UUID v4');
  return key.value;
}

function fromResult(result: PlaceOrderResult): SubmitOutcome {
  switch (result.kind) {
    case 'placed':
      return { kind: 'placed', order: result.order };
    case 'priceChanged':
      return { kind: 'priceChanged' };
    case 'unauthorized':
      return { kind: 'unauthorized' };
    default:
      return { kind: 'refused', refusal: result };
  }
}

/**
 * The checkout of the signed-in shopper: the draft restored from `sessionStorage` and persisted on
 * every change (deleted once confirmed), the state machine of data-model.md §3.2 and the one
 * submission path, which blocks a second click while a request is in flight and reuses the
 * draft's key for a byte-identical retry (FR-008).
 */
export function useCheckout({
  storage = globalThis.sessionStorage,
  uuid = () => globalThis.crypto.randomUUID(),
}: UseCheckoutOptions = {}): CheckoutHandle {
  const { order: port } = usePorts();
  const [state, dispatch] = useReducer(reduceCheckout, storage, (initial) =>
    initialCheckoutState(loadDraft(initial)),
  );
  const latest = useRef(state);
  latest.current = state;

  useEffect(() => {
    if (state.status === 'confirmed') clearDraft(storage);
    else saveDraft(storage, state.draft);
  }, [state.draft, state.status, storage]);

  const submit = useCallback(async (): Promise<SubmitOutcome> => {
    const current = latest.current;
    const request = requestOf(current.draft);
    if (request === undefined || current.inFlight || current.status === 'confirmed') {
      return { kind: 'notSent' };
    }
    const key = keyFor(current.draft, mint(uuid));
    // Mirrors the reducer synchronously, so a second click in the same tick is also blocked.
    latest.current = { ...current, inFlight: true };
    dispatch({ type: 'submitted', fresh: key });
    let result: PlaceOrderResult;
    try {
      result = await port.placeOrder(request, key);
    } catch (error: unknown) {
      dispatch({ type: 'interrupted' });
      return { kind: 'interrupted', error };
    }
    switch (result.kind) {
      case 'placed':
        dispatch({ type: 'placed', order: result.order });
        break;
      case 'priceChanged':
        dispatch({
          type: 'priceChanged',
          change: {
            changedLines: result.changedLines,
            currentCartRevision: result.currentCartRevision,
          },
        });
        break;
      case 'unauthorized':
        dispatch({ type: 'unauthorized' });
        break;
      default:
        dispatch({ type: 'refused', refusal: result });
    }
    return fromResult(result);
  }, [port, uuid]);

  // Stable callbacks (dispatch is stable), so effects may depend on them without re-running.
  const showStep = useCallback((step: CheckoutStep) => {
    dispatch({ type: 'stepShown', step });
  }, []);
  const chooseAddress = useCallback((addressId: string) => {
    dispatch({ type: 'addressChosen', addressId });
  }, []);
  const choosePaymentMethod = useCallback((paymentMethodId: PaymentMethodId) => {
    dispatch({ type: 'paymentMethodChosen', paymentMethodId });
  }, []);
  const seeCart = useCallback((revision: CartRevision) => {
    dispatch({ type: 'cartSeen', revision });
  }, []);
  const acceptPrices = useCallback(() => {
    dispatch({ type: 'pricesAccepted', fresh: mint(uuid) });
  }, [uuid]);
  const adjust = useCallback(() => {
    dispatch({ type: 'adjusted' });
  }, []);

  return useMemo(
    () => ({
      state,
      showStep,
      chooseAddress,
      choosePaymentMethod,
      seeCart,
      acceptPrices,
      adjust,
      submit,
    }),
    [state, showStep, chooseAddress, choosePaymentMethod, seeCart, acceptPrices, adjust, submit],
  );
}
