import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { ROUTE_TEMPLATES, RouteTemplate } from '@domain/routeTemplate';
import { API_ROUTE_TEMPLATES, routeOf } from '@telemetry/routeTemplates';

import { ORIGIN, sensitiveValue } from './support.ts';

const segment = sensitiveValue.map((value) => encodeURIComponent(value));

describe('routeOf', () => {
  it('reduces a storefront URL to the template of its pathname, query and hash dropped', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...ROUTE_TEMPLATES),
        segment,
        fc.string({ maxLength: 30 }),
        (template, id, query) => {
          const pathname = template.replace(':id', id);
          const expected = RouteTemplate.fromPathname(pathname);
          expect(routeOf(`${ORIGIN}${pathname}?${encodeURIComponent(query)}#x`, ORIGIN)).toBe(
            expected,
          );
          expect(routeOf(`${pathname}?q=${encodeURIComponent(query)}`, ORIGIN)).toBe(expected);
        },
      ),
    );
  });

  it('reduces an API URL to the template of the contract', () => {
    expect(
      routeOf(`${ORIGIN}/api/v1/orders/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21/status?x=1`, ORIGIN),
    ).toBe('/api/v1/orders/:id/status');
    expect(routeOf('/api/v1/catalog/products?q=shoes', ORIGIN)).toBe('/api/v1/catalog/products');
    for (const template of API_ROUTE_TEMPLATES) {
      expect(routeOf(template.replaceAll(':id', 'abc'), ORIGIN)).toBe(template);
    }
  });

  it('answers /unknown for another origin, an unknown API path, a deeper path and garbage', () => {
    expect(routeOf('https://evil.example/products/abc', ORIGIN)).toBe('/unknown');
    expect(routeOf('//evil.example/cart', ORIGIN)).toBe('/unknown');
    expect(routeOf(`${ORIGIN}/api/v1/secret/abc`, ORIGIN)).toBe('/unknown');
    expect(routeOf(`${ORIGIN}/api/v1/orders/a/b/c`, ORIGIN)).toBe('/unknown');
    expect(routeOf('http://', ORIGIN)).toBe('/unknown');
    expect(routeOf(`${ORIGIN}/nowhere`, ORIGIN)).toBe('/unknown');
    fc.assert(
      fc.property(fc.string(), (anything) => {
        const route = routeOf(anything, ORIGIN);
        expect([...ROUTE_TEMPLATES, ...API_ROUTE_TEMPLATES, '/unknown']).toContain(route);
      }),
    );
  });

  it('keeps a same-origin absolute URL and a relative one apart from other origins', () => {
    expect(routeOf(`${ORIGIN}/cart`, ORIGIN)).toBe('/cart');
    expect(routeOf('/cart', ORIGIN)).toBe('/cart');
    expect(routeOf('http://localhost:9999/cart', ORIGIN)).toBe('/unknown');
  });
});
