import { isUuid } from '@domain/ids';

// URL-driven browsing state (FR-003, contracts/storefront-routes.md): `page` zero-based as the
// API, `size` only when it is not the platform default, `q` trimmed and capped at the contract's
// maximum. Values that do not fit are ignored (the platform's defaults apply), never corrected.
export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;
export const MAX_SEARCH_LENGTH = 100;
export const UUID_LENGTH = 36;

export type Listing = {
  readonly page?: number;
  readonly size?: number;
};

const NON_NEGATIVE_INTEGER = /^\d+$/;

/** `?page=` as a zero-based page index; anything else (negative, fraction, text) is absent. */
export function parsePage(raw: string | null | undefined): number | undefined {
  if (raw === null || raw === undefined || !NON_NEGATIVE_INTEGER.test(raw)) return undefined;
  const page = Number(raw);
  return Number.isSafeInteger(page) ? page : undefined;
}

/** `?size=` within the contract bounds (1..100); the default size and invalid values are absent. */
export function parseSize(raw: string | null | undefined): number | undefined {
  if (raw === null || raw === undefined || !NON_NEGATIVE_INTEGER.test(raw)) return undefined;
  const size = Number(raw);
  if (size < 1 || size > MAX_PAGE_SIZE || size === DEFAULT_PAGE_SIZE) return undefined;
  return size;
}

/** The search term as it is sent: trimmed, at most 100 characters, trimmed again after the cut. */
export function normalizeSearchTerm(raw: string | null | undefined): string {
  if (raw === null || raw === undefined) return '';
  return raw.trim().slice(0, MAX_SEARCH_LENGTH).trim();
}

/**
 * The id in `/categories/:slugOrId`: a UUID, or a readable slug followed by `-` and the UUID
 * (`garden-tools-5c2f9a7e-...`); the slug is ignored, resolution is by id. Anything else is unknown.
 */
export function catalogIdFromParam(param: string | undefined): string | undefined {
  if (param === undefined) return undefined;
  if (isUuid(param)) return param.toLowerCase();
  const separator = param.length - UUID_LENGTH - 1;
  if (separator < 1 || param[separator] !== '-') return undefined;
  const candidate = param.slice(separator + 1);
  return isUuid(candidate) ? candidate.toLowerCase() : undefined;
}

/** `/products/:id`: a UUID or unknown. */
export function productIdFromParam(param: string | undefined): string | undefined {
  return param !== undefined && isUuid(param) ? param.toLowerCase() : undefined;
}

export function listingFromSearch(search: URLSearchParams): Listing {
  const page = parsePage(search.get('page'));
  const size = parseSize(search.get('size'));
  return {
    ...(page === undefined ? {} : { page }),
    ...(size === undefined ? {} : { size }),
  };
}

/** The same query string with `page` replaced (removed when zero) and every other parameter kept. */
export function withPage(search: URLSearchParams, page: number): URLSearchParams {
  const next = new URLSearchParams(search);
  if (page <= 0) next.delete('page');
  else next.set('page', String(page));
  return next;
}

/** Number of pages a listing has, at least one so "page 1 of 1" renders for an empty list. */
export function pageCount(totalItems: number, size: number): number {
  if (size <= 0) return 1;
  return Math.max(1, Math.ceil(totalItems / size));
}
