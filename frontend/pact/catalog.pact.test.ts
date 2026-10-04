import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createCatalogApi } from '@api/catalog';

import { pactFor } from './pact.config.ts';

// Storefront → catalog consumer pact: interactions C1–C5 of contracts/pact-matrix.md, provider
// states verbatim, driving the real src/api/catalog.ts against the Pact mock server. Written to
// build/pacts/storefront-catalog.json at the repository root for the catalog provider to verify.
const { boolean, eachLike, integer, like, regex, string } = MatchersV3;

const PRODUCTS = '/api/v1/catalog/products';
const CATEGORIES = '/api/v1/catalog/categories';
const PROBLEM = 'application/problem+json';
const UUID_PATTERN =
  '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';
const INSTANT_PATTERN =
  '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$';

const CATEGORY_ID = '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45';
const PRODUCT_ID = '0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21';
const MISSING_ID = '00000000-0000-4000-8000-000000000000';

const uuid = (example: string) => regex(UUID_PATTERN, example);
const instant = (example: string) => regex(INSTANT_PATTERN, example);

function product(overrides: { inStock?: boolean; name?: string } = {}) {
  return {
    id: uuid(PRODUCT_ID),
    name: string(overrides.name ?? 'Trail Running Shoes'),
    description: like('Lightweight shoes with a grippy sole.'),
    price: { amountMinor: integer(8990), currency: regex('^[A-Z]{3}$', 'BRL') },
    categoryId: uuid(CATEGORY_ID),
    status: 'active',
    images: eachLike({
      id: uuid('91d3a0b2-7c4e-4f15-b6a8-2e0d5c1f3a67'),
      url: like('https://cdn.example.test/shoes.jpg'),
      altText: like('Shoes'),
      primary: boolean(true),
    }),
    availability: { inStock: boolean(overrides.inStock ?? true) },
    createdAt: instant('2026-10-02T08:00:00Z'),
    updatedAt: instant('2026-10-02T08:00:00Z'),
  };
}

function productPage(page: number, size: number, totalItems: number, name?: string) {
  return {
    items: eachLike(product(name === undefined ? {} : { name })),
    page: integer(page),
    size: integer(size),
    totalItems: integer(totalItems),
  };
}

const emptyPage = { items: [], page: integer(0), size: integer(20), totalItems: integer(0) };

function category() {
  return {
    id: uuid(CATEGORY_ID),
    name: string('Footwear'),
    description: like('Shoes and boots'),
    status: 'active',
  };
}

function notFound(detail: string) {
  return {
    type: regex('^.*/problems/not-found$', 'https://ecommerce.example/problems/not-found'),
    title: like('Not found'),
    status: 404,
    detail: like(detail),
  };
}

const provider = pactFor('catalog');

describe('storefront → catalog pact (C1–C5)', () => {
  describe('C1 listProducts', () => {
    it('lists the first page with default paging', async () => {
      await provider
        .addInteraction()
        .given('the catalogue has 25 active products')
        .uponReceiving('a request for the first page of products with default paging')
        .withRequest('GET', PRODUCTS)
        .willRespondWith(200, (response) => {
          response.jsonBody(productPage(0, 20, 25));
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listProducts({});
          expect(page.page).toBe(0);
          expect(page.size).toBe(20);
          expect(page.totalItems).toBe(25);
          expect(page.items[0]?.availability.inStock).toBe(true);
          expect(page.items[0]?.images[0]?.primary).toBe(true);
        });
    });

    it('lists a given page and size', async () => {
      await provider
        .addInteraction()
        .given('the catalogue has 25 active products')
        .uponReceiving('a request for the third page of 10 products')
        .withRequest('GET', PRODUCTS, (request) => {
          request.query({ page: '2', size: '10' });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(productPage(2, 10, 25));
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listProducts({
            page: 2,
            size: 10,
          });
          expect(page.page).toBe(2);
          expect(page.size).toBe(10);
        });
    });

    it('receives an empty page when the catalogue has no products', async () => {
      await provider
        .addInteraction()
        .given('the catalogue has no products')
        .uponReceiving('a request for products when the catalogue is empty')
        .withRequest('GET', PRODUCTS)
        .willRespondWith(200, (response) => {
          response.jsonBody(emptyPage);
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listProducts({});
          expect(page.items).toEqual([]);
          expect(page.totalItems).toBe(0);
        });
    });
  });

  describe('C2 listProducts with q and categoryId', () => {
    it('searches by name, ranked as the platform returns', async () => {
      await provider
        .addInteraction()
        .given('the catalogue has active products matching "shoes"')
        .uponReceiving('a search for "shoes"')
        .withRequest('GET', PRODUCTS, (request) => {
          request.query({ q: 'shoes' });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(productPage(0, 20, 2, 'Trail Running Shoes'));
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listProducts({
            q: 'shoes',
          });
          expect(page.items.length).toBeGreaterThan(0);
          expect(page.items[0]?.name).toBe('Trail Running Shoes');
        });
    });

    it('lists the products of a category and its descendants', async () => {
      await provider
        .addInteraction()
        .given(`category ${CATEGORY_ID} has 3 products`)
        .uponReceiving(`a request for the products of category ${CATEGORY_ID}`)
        .withRequest('GET', PRODUCTS, (request) => {
          request.query({ categoryId: CATEGORY_ID });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(productPage(0, 20, 3));
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listProducts({
            categoryId: CATEGORY_ID,
          });
          expect(page.totalItems).toBe(3);
          expect(page.items[0]?.categoryId).toBe(CATEGORY_ID);
        });
    });
  });

  describe('C3 listCategories', () => {
    it('lists the categories', async () => {
      await provider
        .addInteraction()
        .given('the catalogue has 3 categories')
        .uponReceiving('a request for the categories')
        .withRequest('GET', CATEGORIES)
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: eachLike(category()),
            page: integer(0),
            size: integer(20),
            totalItems: integer(3),
          });
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listCategories({});
          expect(page.totalItems).toBe(3);
          expect(page.items[0]?.name).toBe('Footwear');
          expect(page.items[0]?.status).toBe('active');
        });
    });

    it('receives an empty page when there are no categories', async () => {
      await provider
        .addInteraction()
        .given('the catalogue has no categories')
        .uponReceiving('a request for the categories when there are none')
        .withRequest('GET', CATEGORIES)
        .willRespondWith(200, (response) => {
          response.jsonBody(emptyPage);
        })
        .executeTest(async (mockServer) => {
          const page = await createCatalogApi({ baseUrl: mockServer.url }).listCategories({});
          expect(page.items).toEqual([]);
        });
    });
  });

  describe('C4 getCategory', () => {
    it('reads an existing category', async () => {
      await provider
        .addInteraction()
        .given(`category ${CATEGORY_ID} exists`)
        .uponReceiving(`a request for category ${CATEGORY_ID}`)
        .withRequest('GET', `${CATEGORIES}/${CATEGORY_ID}`)
        .willRespondWith(200, (response) => {
          response.jsonBody(category());
        })
        .executeTest(async (mockServer) => {
          const found = await createCatalogApi({ baseUrl: mockServer.url }).getCategory(
            CATEGORY_ID,
          );
          expect(found?.id).toBe(CATEGORY_ID);
          expect(found?.name).toBe('Footwear');
        });
    });

    it('treats a missing or withdrawn category as not found', async () => {
      await provider
        .addInteraction()
        .given(`category ${MISSING_ID} does not exist`)
        .uponReceiving(`a request for the unknown category ${MISSING_ID}`)
        .withRequest('GET', `${CATEGORIES}/${MISSING_ID}`)
        .willRespondWith(404, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(notFound('Category not found.'));
        })
        .executeTest(async (mockServer) => {
          const found = await createCatalogApi({ baseUrl: mockServer.url }).getCategory(MISSING_ID);
          expect(found).toBeNull();
        });
    });
  });

  describe('C5 getProduct', () => {
    it('reads a product in stock', async () => {
      await provider
        .addInteraction()
        .given(`product ${PRODUCT_ID} is active with stock 5`)
        .uponReceiving(`a request for product ${PRODUCT_ID} in stock`)
        .withRequest('GET', `${PRODUCTS}/${PRODUCT_ID}`)
        .willRespondWith(200, (response) => {
          response.jsonBody(product({ inStock: true }));
        })
        .executeTest(async (mockServer) => {
          const found = await createCatalogApi({ baseUrl: mockServer.url }).getProduct(PRODUCT_ID);
          expect(found?.id).toBe(PRODUCT_ID);
          expect(found?.availability.inStock).toBe(true);
          expect(found?.availability.availableQuantity).toBeUndefined();
        });
    });

    it('reads a product out of stock (not addable)', async () => {
      await provider
        .addInteraction()
        .given(`product ${PRODUCT_ID} is active with stock 0`)
        .uponReceiving(`a request for product ${PRODUCT_ID} out of stock`)
        .withRequest('GET', `${PRODUCTS}/${PRODUCT_ID}`)
        .willRespondWith(200, (response) => {
          response.jsonBody(product({ inStock: false }));
        })
        .executeTest(async (mockServer) => {
          const found = await createCatalogApi({ baseUrl: mockServer.url }).getProduct(PRODUCT_ID);
          expect(found?.availability.inStock).toBe(false);
        });
    });

    it('treats a withdrawn product as not found', async () => {
      await provider
        .addInteraction()
        .given(`product ${PRODUCT_ID} is withdrawn`)
        .uponReceiving(`a request for the withdrawn product ${PRODUCT_ID}`)
        .withRequest('GET', `${PRODUCTS}/${PRODUCT_ID}`)
        .willRespondWith(404, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(notFound('Product not found.'));
        })
        .executeTest(async (mockServer) => {
          const found = await createCatalogApi({ baseUrl: mockServer.url }).getProduct(PRODUCT_ID);
          expect(found).toBeNull();
        });
    });

    it('treats an unknown product as not found', async () => {
      await provider
        .addInteraction()
        .given(`product ${MISSING_ID} does not exist`)
        .uponReceiving(`a request for the unknown product ${MISSING_ID}`)
        .withRequest('GET', `${PRODUCTS}/${MISSING_ID}`)
        .willRespondWith(404, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(notFound('Product not found.'));
        })
        .executeTest(async (mockServer) => {
          const found = await createCatalogApi({ baseUrl: mockServer.url }).getProduct(MISSING_ID);
          expect(found).toBeNull();
        });
    });
  });
});
