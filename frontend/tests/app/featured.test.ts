import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import type { Category } from '@app/catalog/catalogPort';
import { MAX_FEATURED_CATEGORIES, selectFeaturedCategories } from '@app/catalog/featured';

const category = fc.record(
  {
    id: fc.uuid(),
    name: fc.string({ minLength: 1, maxLength: 20 }),
    status: fc.constantFrom('active', 'withdrawn'),
    parentId: fc.oneof(fc.constant(null), fc.uuid()),
  },
  { requiredKeys: ['id', 'name', 'status'] },
) as fc.Arbitrary<Category>;

const categories = fc.uniqueArray(category, { selector: (item) => item.id, maxLength: 20 });

const qualifies = (item: Category): boolean =>
  item.status === 'active' && (item.parentId === undefined || item.parentId === null);

describe('selectFeaturedCategories (US1: editorial home)', () => {
  it('returns at most four entries', () => {
    expect(MAX_FEATURED_CATEGORIES).toBe(4);
    fc.assert(
      fc.property(categories, (list) => {
        expect(selectFeaturedCategories(list).length).toBeLessThanOrEqual(4);
      }),
    );
  });

  it('contains only active categories without a parent', () => {
    fc.assert(
      fc.property(categories, (list) => {
        for (const item of selectFeaturedCategories(list)) {
          expect(item.status).toBe('active');
          expect(item.parentId ?? null).toBeNull();
        }
      }),
    );
  });

  it('is the first four qualifying categories in catalogue order (a stable subsequence)', () => {
    fc.assert(
      fc.property(categories, (list) => {
        expect(selectFeaturedCategories(list)).toEqual(list.filter(qualifies).slice(0, 4));
      }),
    );
  });

  it('is empty when none qualify', () => {
    fc.assert(
      fc.property(categories, (list) => {
        const none = list.filter((item) => !qualifies(item));
        expect(selectFeaturedCategories(none)).toEqual([]);
      }),
    );
    expect(selectFeaturedCategories([])).toEqual([]);
  });

  it('keeps everything that qualifies when there are four or fewer', () => {
    fc.assert(
      fc.property(categories, (list) => {
        const few = list.filter(qualifies).slice(0, 4);
        expect(selectFeaturedCategories(few)).toEqual(few);
      }),
    );
  });

  it('does not mutate its input', () => {
    const list: Category[] = [
      { id: 'a', name: 'A', status: 'active' },
      { id: 'b', name: 'B', status: 'withdrawn' },
    ];
    const copy = structuredClone(list);
    selectFeaturedCategories(list);
    expect(list).toEqual(copy);
  });
});
