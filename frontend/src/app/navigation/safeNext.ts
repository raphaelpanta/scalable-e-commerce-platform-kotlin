import { RouteTemplate } from '@domain/routeTemplate';

// Redirect rule of contracts/storefront-routes.md. `next` is accepted only when it starts with
// exactly one `/`, contains no scheme, host, backslash, control character or encoded form of
// those after one decoding, and its path matches a storefront route other than the
// authentication routes. Anything else falls back to `/`.
export const HOME = '/';
export const SIGN_IN_PATH = '/sign-in';

// Control characters (U+0000–U+001F and U+007F) are never part of a route.
function hasControlCharacter(value: string): boolean {
  for (const character of value) {
    const code = character.codePointAt(0) ?? 0;
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}
const WHITESPACE = /\s/;

function decodeOnce(raw: string): string | undefined {
  try {
    return decodeURIComponent(raw);
  } catch {
    return undefined;
  }
}

function pathOf(target: string): string {
  const end = Math.min(
    ...['?', '#'].map((marker) => {
      const index = target.indexOf(marker);
      return index === -1 ? target.length : index;
    }),
  );
  return target.slice(0, end);
}

export function isSafeNext(raw: string | null | undefined): raw is string {
  if (typeof raw !== 'string' || raw.length === 0) return false;
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return false;
  const decoded = decodeOnce(raw);
  if (decoded === undefined) return false;
  if (!decoded.startsWith('/') || decoded.startsWith('//') || decoded.startsWith('/\\'))
    return false;
  if (decoded.includes('\\') || hasControlCharacter(decoded)) return false;
  const path = pathOf(decoded);
  if (path.includes('//') || path.includes(':') || WHITESPACE.test(path)) return false;
  const template = RouteTemplate.fromPathname(path);
  return RouteTemplate.isKnown(template) && !RouteTemplate.isAuthRoute(template);
}

/** The accepted `next` (as given, query included) or `/`. */
export function safeNext(raw: string | null | undefined): string {
  return isSafeNext(raw) ? raw : HOME;
}

/** The sign-in location that returns to `pathAndSearch` after a successful sign-in. */
export function signInLocationFor(pathAndSearch: string): string {
  return isSafeNext(pathAndSearch)
    ? `${SIGN_IN_PATH}?next=${encodeURIComponent(pathAndSearch)}`
    : SIGN_IN_PATH;
}
