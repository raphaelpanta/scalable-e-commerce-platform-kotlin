import { CartRevision } from '@domain/cartRevision';
import { IdempotencyKey, isUuid } from '@domain/ids';
import { PaymentMethod } from '@domain/paymentMethods';

import {
  CHECKOUT_STEPS,
  type CheckoutDraft,
  type CheckoutStep,
  EMPTY_DRAFT,
} from './checkoutDraft.ts';

// The checkout draft in `sessionStorage` under one key (data-model.md §3.2, FR-006, FR-008):
// exactly `step`, `addressId`, `paymentMethodId`, `acknowledgedRevision` and `idempotencyKey`,
// so a reload or a sign-in round trip keeps the draft and the key. Never an address text, an
// email, a card number or a token: the serialiser only knows these five members, and the parser
// drops anything else (and any member that fails its value object).
export const DRAFT_STORAGE_KEY = 'storefront.checkout.draft';

export const DRAFT_MEMBERS = [
  'step',
  'addressId',
  'paymentMethodId',
  'acknowledgedRevision',
  'idempotencyKey',
] as const;

/** The part of the Web Storage interface the draft needs; `sessionStorage` at the edge. */
export type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

type StoredDraft = {
  readonly step: CheckoutStep;
  readonly addressId?: string;
  readonly paymentMethodId?: string;
  readonly acknowledgedRevision?: string;
  readonly idempotencyKey?: string;
};

export function serializeDraft(draft: CheckoutDraft): string {
  const stored: StoredDraft = {
    step: draft.step,
    ...(draft.addressId === undefined ? {} : { addressId: draft.addressId }),
    ...(draft.paymentMethodId === undefined ? {} : { paymentMethodId: draft.paymentMethodId }),
    ...(draft.acknowledgedRevision === undefined
      ? {}
      : { acknowledgedRevision: draft.acknowledgedRevision.value }),
    ...(draft.idempotencyKey === undefined ? {} : { idempotencyKey: draft.idempotencyKey.value }),
  };
  return JSON.stringify(stored);
}

function isStep(candidate: unknown): candidate is CheckoutStep {
  return typeof candidate === 'string' && (CHECKOUT_STEPS as readonly string[]).includes(candidate);
}

function text(candidate: unknown): string | undefined {
  return typeof candidate === 'string' ? candidate : undefined;
}

/** The draft a stored value describes; `EMPTY_DRAFT` for anything unreadable. */
export function parseDraft(raw: string | null): CheckoutDraft {
  if (raw === null) return EMPTY_DRAFT;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return EMPTY_DRAFT;
  }
  if (typeof parsed !== 'object' || parsed === null) return EMPTY_DRAFT;
  const record = parsed as Record<string, unknown>;
  const addressId = text(record['addressId']);
  const paymentMethodId = text(record['paymentMethodId']);
  const revision = CartRevision.parse(text(record['acknowledgedRevision']) ?? '');
  const key = IdempotencyKey.parse(text(record['idempotencyKey']) ?? '');
  return {
    step: isStep(record['step']) ? record['step'] : 'address',
    ...(addressId !== undefined && isUuid(addressId) ? { addressId } : {}),
    ...(paymentMethodId !== undefined && PaymentMethod.isId(paymentMethodId)
      ? { paymentMethodId }
      : {}),
    ...(revision.ok ? { acknowledgedRevision: revision.value } : {}),
    ...(key.ok ? { idempotencyKey: key.value } : {}),
  };
}

export function saveDraft(storage: DraftStorage, draft: CheckoutDraft): void {
  try {
    storage.setItem(DRAFT_STORAGE_KEY, serializeDraft(draft));
  } catch {
    // Storage full or unavailable: the draft lives in memory for this page only.
  }
}

export function loadDraft(storage: DraftStorage): CheckoutDraft {
  try {
    return parseDraft(storage.getItem(DRAFT_STORAGE_KEY));
  } catch {
    return EMPTY_DRAFT;
  }
}

export function clearDraft(storage: DraftStorage): void {
  try {
    storage.removeItem(DRAFT_STORAGE_KEY);
  } catch {
    // Nothing to clear when the storage is unavailable.
  }
}
