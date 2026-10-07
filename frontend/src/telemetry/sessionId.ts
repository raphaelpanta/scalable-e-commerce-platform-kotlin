import { isCanonicalUuidV4, type UuidSource } from '@domain/ids';

// The telemetry session id (data-model.md §3.5): a random UUID kept in `sessionStorage`, so a tab
// keeps one id across reloads and a new tab starts a new one. It is minted here and never derived
// from, or related to, the gateway's session cookie; `localStorage` is never touched.
export const SESSION_ID_KEY = 'storefront.telemetry.sessionId';

export type SessionStorageLike = Pick<Storage, 'getItem' | 'setItem'>;

function read(storage: SessionStorageLike | undefined): string | undefined {
  try {
    const stored = storage?.getItem(SESSION_ID_KEY) ?? undefined;
    return stored !== undefined && isCanonicalUuidV4(stored) ? stored : undefined;
  } catch {
    return undefined;
  }
}

function write(storage: SessionStorageLike | undefined, id: string): void {
  try {
    storage?.setItem(SESSION_ID_KEY, id);
  } catch {
    // Storage blocked or full: the id then lives for the page view only.
  }
}

/** The stored id, or a fresh random UUID v4 that is stored; storage failures never surface. */
export function sessionId(storage: SessionStorageLike | undefined, uuid: UuidSource): string {
  const existing = read(storage);
  if (existing !== undefined) return existing;
  const id = uuid().toLowerCase();
  const minted = isCanonicalUuidV4(id) ? id : globalThis.crypto.randomUUID();
  write(storage, minted);
  return minted;
}
