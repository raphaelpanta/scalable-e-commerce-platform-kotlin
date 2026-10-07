import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  catalogIdFromParam,
  categoryPath,
  DEFAULT_PAGE_SIZE,
  listingFromSearch,
  MAX_PAGE_SIZE,
  MAX_SEARCH_LENGTH,
  MAX_SLUG_LENGTH,
  normalizeSearchTerm,
  pageCount,
  parsePage,
  parseSize,
  productIdFromParam,
  productPath,
  slugOf,
  withPage,
} from '@app/catalog/browseParams';
import { catalogKeys } from '@app/catalog/catalogQueries';

const slug = fc.stringMatching(/^[a-z0-9]+(-[a-z0-9]+){0,3}$/);

describe('browse parameters (FR-003, storefront-routes.md)', () => {
  it('parsePage accepts every zero-based page index and nothing else', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 1_000_000 }), (page) => {
        expect(parsePage(String(page))).toBe(page);
      }),
    );
    for (const raw of ['-1', '1.5', 'two', '', ' 3', '3 ', null, undefined, '1e3']) {
      expect(parsePage(raw)).toBeUndefined();
    }
  });

  it('parseSize keeps sizes within 1..100 other than the default and drops the rest', () => {
    fc.assert(
      fc.property(fc.integer({ min: 1, max: MAX_PAGE_SIZE }), (size) => {
        expect(parseSize(String(size))).toBe(size === DEFAULT_PAGE_SIZE ? undefined : size);
      }),
    );
    fc.assert(
      fc.property(
        fc.oneof(fc.integer({ max: 0 }), fc.integer({ min: MAX_PAGE_SIZE + 1 })),
        (size) => {
          expect(parseSize(String(size))).toBeUndefined();
        },
      ),
    );
    expect(parseSize('abc')).toBeUndefined();
    expect(parseSize(null)).toBeUndefined();
  });

  it('normalizeSearchTerm trims, caps at 100 characters and is idempotent', () => {
    fc.assert(
      fc.property(fc.string({ maxLength: 300 }), (raw) => {
        const term = normalizeSearchTerm(raw);
        expect(term.length).toBeLessThanOrEqual(MAX_SEARCH_LENGTH);
        expect(term).toBe(term.trim());
        expect(normalizeSearchTerm(term)).toBe(term);
        expect(raw.trim().startsWith(term)).toBe(true);
      }),
    );
    expect(normalizeSearchTerm(`  ${'a'.repeat(150)}  `)).toBe('a'.repeat(100));
    // The cut can leave a trailing space behind; it is trimmed again.
    expect(normalizeSearchTerm(`${'a'.repeat(99)} b`)).toBe('a'.repeat(99));
    expect(normalizeSearchTerm(null)).toBe('');
    expect(normalizeSearchTerm(undefined)).toBe('');
    expect(normalizeSearchTerm('   ')).toBe('');
  });

  it('catalogIdFromParam resolves a UUID with or without a readable slug prefix, in either case', () => {
    fc.assert(
      fc.property(fc.uuid(), slug, fc.boolean(), (id, prefix, upper) => {
        const written = upper ? id.toUpperCase() : id;
        expect(catalogIdFromParam(written)).toBe(id.toLowerCase());
        expect(catalogIdFromParam(`${prefix}-${written}`)).toBe(id.toLowerCase());
        expect(productIdFromParam(written)).toBe(id.toLowerCase());
        expect(productIdFromParam(`${prefix}-${written}`)).toBeUndefined();
      }),
    );
    for (const raw of ['', 'not-a-uuid', '-0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21', undefined]) {
      expect(catalogIdFromParam(raw)).toBeUndefined();
      expect(productIdFromParam(raw)).toBeUndefined();
    }
    expect(catalogIdFromParam('garden_tools-0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21')).toBe(
      '0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21',
    );
    expect(catalogIdFromParam('shoes 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21')).toBeUndefined();
  });

  it('categoryPath yields a slug the category route resolves back to the same id', () => {
    fc.assert(
      fc.property(fc.uuid(), fc.string({ maxLength: 80 }), (id, name) => {
        const path = categoryPath({ id, name });
        expect(path.startsWith('/categories/')).toBe(true);
        expect(catalogIdFromParam(path.slice('/categories/'.length))).toBe(id.toLowerCase());
        const slug = slugOf(name);
        expect(slug).toMatch(/^(?:[a-z0-9]+(?:-[a-z0-9]+)*)?$/);
        expect(slug.length).toBeLessThanOrEqual(MAX_SLUG_LENGTH);
        expect(path).toBe(slug === '' ? `/categories/${id}` : `/categories/${slug}-${id}`);
      }),
    );
    expect(slugOf('Garden tools')).toBe('garden-tools');
    expect(slugOf('  Café & Bar!! ')).toBe('cafe-bar');
    expect(slugOf('***')).toBe('');
    expect(slugOf(`${'a'.repeat(39)}-b`)).toBe('a'.repeat(39));
    expect(productPath({ id: 'x' })).toBe('/products/x');
  });

  it('listingFromSearch and withPage round-trip the page while keeping the other parameters', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 0, max: 500 }),
        fc.integer({ min: 1, max: MAX_PAGE_SIZE }),
        fc.string({ maxLength: 20 }),
        (page, size, q) => {
          const search = withPage(new URLSearchParams({ size: String(size), q }), page);
          expect(search.get('q')).toBe(q);
          expect(search.get('size')).toBe(String(size));
          expect(search.has('page')).toBe(page > 0);
          const listing = listingFromSearch(search);
          expect(listing.page ?? 0).toBe(page);
          expect(listing.size).toBe(size === DEFAULT_PAGE_SIZE ? undefined : size);
          // Absent parameters are absent keys, not `undefined` values (query keys, spreads).
          expect(Object.keys(listing).sort()).toEqual(
            [...(page > 0 ? ['page'] : []), ...(size === DEFAULT_PAGE_SIZE ? [] : ['size'])].sort(),
          );
        },
      ),
    );
    expect(listingFromSearch(new URLSearchParams(''))).toStrictEqual({});
    expect(listingFromSearch(new URLSearchParams('page=-2&size=0'))).toStrictEqual({});
    expect(listingFromSearch(new URLSearchParams('page=3'))).toStrictEqual({ page: 3 });
  });

  it('pageCount is at least one and covers every item', () => {
    fc.assert(
      fc.property(fc.nat({ max: 10_000 }), fc.integer({ min: 1, max: 100 }), (total, size) => {
        const pages = pageCount(total, size);
        expect(pages).toBeGreaterThanOrEqual(1);
        expect(pages * size).toBeGreaterThanOrEqual(total);
        expect((pages - 1) * size).toBeLessThan(Math.max(total, 1));
      }),
    );
    expect(pageCount(0, 20)).toBe(1);
    expect(pageCount(3, 2)).toBe(2);
    expect(pageCount(5, 0)).toBe(1);
  });

  it('query keys differ whenever any URL parameter differs', () => {
    const params = fc.record(
      {
        page: fc.nat({ max: 50 }),
        size: fc.integer({ min: 1, max: 100 }),
        q: fc.string({ maxLength: 10 }),
        categoryId: fc.uuid(),
      },
      { requiredKeys: [] },
    );
    fc.assert(
      fc.property(params, params, (left, right) => {
        const same =
          JSON.stringify(catalogKeys.products(left)) ===
          JSON.stringify(catalogKeys.products(right));
        const equal =
          left.page === right.page &&
          left.size === right.size &&
          left.q === right.q &&
          left.categoryId === right.categoryId;
        expect(same).toBe(equal);
      }),
    );
    expect(catalogKeys.products({})).toEqual([
      'catalog',
      'products',
      { page: null, size: null, q: null, categoryId: null },
    ]);
  });
});
