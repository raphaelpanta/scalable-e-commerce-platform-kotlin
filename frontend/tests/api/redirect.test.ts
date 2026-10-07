import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { isSafeNext, safeNext, signInLocationFor } from '@app/navigation/safeNext';
import { ROUTE_TEMPLATES } from '@domain/routeTemplate';

const PROTECTED_OR_PUBLIC = ROUTE_TEMPLATES.filter(
  (t) =>
    !['/sign-in', '/register', '/verify-email', '/forgot-password', '/reset-password'].includes(t),
);
const uuid = fc.uuid({ version: 4 });

describe('safeNext (contracts/storefront-routes.md redirect rule)', () => {
  it('rejects the documented examples', () => {
    const rejected = [
      '//evil.example',
      '/\\evil.example',
      'https://evil.example',
      'javascript:alert(1)',
      '/%2F%2Fevil.example',
      '/%5Cevil.example',
      '/api/v1/identity/accounts/me',
      '/unknown',
      '/sign-in',
      '/register',
      '/verify-email?token=abc',
      '/forgot-password',
      '/reset-password?token=abc',
      '',
      '/cart\u0000',
      '/cart\n',
      '/cart%0A',
      '/products/%2F%2Fevil',
      '/products//evil',
      '/orders/javascript:x',
      '/%zz',
      'cart',
      // The raw value must start with `/` even when its decoded form does.
      '%2Fcart',
      // Control characters in the query (not whitespace, so the path check alone would pass).
      '/cart?x=%01',
      '/cart?x=%7F',
      '/cart?x=\u0001',
    ];
    for (const candidate of rejected) {
      expect(isSafeNext(candidate), candidate).toBe(false);
      expect(safeNext(candidate), candidate).toBe('/');
    }
    expect(safeNext(null)).toBe('/');
    expect(safeNext(undefined)).toBe('/');
  });

  it('accepts only paths of the route list, keeping their query', () => {
    const accepted = [
      '/',
      '/cart',
      '/checkout?step=review',
      '/orders?page=2&size=20',
      '/account/addresses',
      '/console/stock?q=mug',
      '/search?q=a%20b',
    ];
    for (const candidate of accepted) expect(safeNext(candidate), candidate).toBe(candidate);
    fc.assert(
      fc.property(
        fc.constantFrom(...PROTECTED_OR_PUBLIC),
        uuid,
        fc.option(fc.stringMatching(/^[a-z0-9=&]{0,20}$/), { nil: undefined }),
        (template, id, q) => {
          const path = template.replace(':id', id) + (q === undefined ? '' : `?${q}`);
          expect(safeNext(path)).toBe(path);
        },
      ),
    );
  });

  it('never returns a target with a host, scheme or backslash whatever the input', () => {
    fc.assert(
      fc.property(fc.oneof(fc.webUrl(), fc.string(), fc.webPath()), (raw) => {
        const target = safeNext(raw);
        expect(target.startsWith('/')).toBe(true);
        expect(target.startsWith('//')).toBe(false);
        expect(target.includes('\\')).toBe(false);
        expect(/^\/[^/]*:/.test(target)).toBe(false);
        expect(decodeURIComponent(target).startsWith('//')).toBe(false);
      }),
    );
  });

  it('builds the sign-in location with the encoded return path, or plain /sign-in when unsafe', () => {
    expect(signInLocationFor('/checkout?step=review')).toBe(
      '/sign-in?next=%2Fcheckout%3Fstep%3Dreview',
    );
    expect(signInLocationFor('/sign-in')).toBe('/sign-in');
    expect(signInLocationFor('//evil')).toBe('/sign-in');
    fc.assert(
      fc.property(fc.constantFrom(...PROTECTED_OR_PUBLIC), uuid, (template, id) => {
        const path = template.replace(':id', id);
        const location = signInLocationFor(path);
        const next = new URLSearchParams(location.slice(location.indexOf('?'))).get('next');
        expect(next).toBe(path);
        expect(safeNext(next)).toBe(path);
      }),
    );
  });
});
