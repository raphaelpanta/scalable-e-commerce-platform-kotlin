import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { ROUTE_TEMPLATES, RouteTemplate, UNKNOWN_ROUTE } from '@domain/routeTemplate';

// A path segment that is not a template literal and carries no separators.
const segment = fc
  .string({ minLength: 1, maxLength: 40 })
  .filter((s) => !s.includes('/') && !s.includes('?') && !s.includes('#') && s !== ':id');
// Ids long enough that they cannot accidentally be a substring of a template literal.
const idLike = fc.oneof(
  fc.uuid(),
  segment.filter((s) => s.length >= 12 && !ROUTE_TEMPLATES.some((t) => t.includes(s))),
);
const query = fc.option(
  fc.string({ maxLength: 40 }).map((q) => `?${encodeURIComponent(q)}`),
  { nil: '' },
);
const hash = fc.option(
  fc.string({ maxLength: 10 }).map((h) => `#${encodeURIComponent(h)}`),
  { nil: '' },
);

const dynamicTemplates = ROUTE_TEMPLATES.filter((t) => t.includes(':id'));
const staticTemplates = ROUTE_TEMPLATES.filter((t) => !t.includes(':id'));

function instantiate(template: string, id: string): string {
  return template.replace(':id', encodeURIComponent(id));
}

describe('RouteTemplate.fromPathname (data-model.md §2.2)', () => {
  it('matches the closed list of storefront-routes.md', () => {
    expect([...ROUTE_TEMPLATES]).toEqual([
      '/',
      '/categories/:id',
      '/search',
      '/products/:id',
      '/cart',
      '/sign-in',
      '/register',
      '/verify-email',
      '/verify',
      '/reset-password',
      '/forgot-password',
      '/checkout',
      '/orders',
      '/orders/:id',
      '/orders/:id/confirmation',
      '/account',
      '/account/addresses',
      '/account/notifications',
      '/console/orders',
      '/console/orders/:id',
      '/console/stock',
    ]);
    expect(UNKNOWN_ROUTE).toBe('/unknown');
  });

  it('maps every static route to itself, with or without a trailing slash, query or hash', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...staticTemplates),
        fc.boolean(),
        query,
        hash,
        (t, slash, q, h) => {
          const path = (t === '/' ? '/' : slash ? `${t}/` : t) + q + h;
          expect(RouteTemplate.fromPathname(path)).toBe(t);
        },
      ),
    );
  });

  it('replaces any id, token or uuid by :id and never lets it survive', () => {
    fc.assert(
      fc.property(fc.constantFrom(...dynamicTemplates), idLike, query, (template, id, q) => {
        const result = RouteTemplate.fromPathname(instantiate(template, id) + q);
        expect(result).toBe(template);
        expect(result.includes(encodeURIComponent(id))).toBe(false);
        expect(result.includes('?')).toBe(false);
      }),
    );
  });

  it('never returns a query string or hash', () => {
    fc.assert(
      fc.property(fc.webPath(), query, hash, (path, q, h) => {
        const result = RouteTemplate.fromPathname(path + q + h);
        expect(result.includes('?')).toBe(false);
        expect(result.includes('#')).toBe(false);
        expect([...ROUTE_TEMPLATES, UNKNOWN_ROUTE]).toContain(result);
      }),
    );
  });

  it('returns /unknown for anything outside the list', () => {
    const unknownPaths = [
      '/api/v1/catalog/products',
      '/products',
      '/products/1/images',
      '/console',
      '/console/stock/x',
      '/orders/1/2',
      '/unknown',
      'products/1',
      '',
      '/categories',
      '/account/x',
    ];
    for (const path of unknownPaths) expect(RouteTemplate.fromPathname(path)).toBe(UNKNOWN_ROUTE);
    fc.assert(
      fc.property(fc.constantFrom(...ROUTE_TEMPLATES), segment, (template, extra) => {
        const extended = instantiate(`${template}/${extra}`, 'x');
        const result = RouteTemplate.fromPathname(extended);
        // Extending a template by one segment lands on another template only where the list
        // defines a child (orders/:id/confirmation, account/*, console/*); never on the parent.
        expect(result).not.toBe(template);
      }),
    );
  });

  it('isKnown and isAuthRoute classify the templates', () => {
    expect(RouteTemplate.isKnown(UNKNOWN_ROUTE)).toBe(false);
    fc.assert(
      fc.property(fc.constantFrom(...ROUTE_TEMPLATES), (t) => {
        expect(RouteTemplate.isKnown(t)).toBe(true);
        expect(RouteTemplate.isAuthRoute(t)).toBe(
          [
            '/sign-in',
            '/register',
            '/verify-email',
            '/verify',
            '/forgot-password',
            '/reset-password',
          ].includes(t),
        );
      }),
    );
  });
});
