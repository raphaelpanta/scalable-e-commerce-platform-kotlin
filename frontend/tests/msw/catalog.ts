import { http, HttpResponse } from 'msw';

import type { Category, Product } from '@app/catalog/catalogPort';

// A small in-memory catalogue behind MSW for the component tests: the default handlers answer
// like the catalog contract (paging, name order, `q` over name and description, `categoryId`,
// 404 problems), tests override single answers with `server.use(...)`.
export const API = 'http://localhost';
export const PRODUCTS_URL = `${API}/api/v1/catalog/products`;
export const CATEGORIES_URL = `${API}/api/v1/catalog/categories`;
export const PROBLEM = 'application/problem+json';

export const gardenTools: Category = {
  id: '5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45',
  name: 'Garden tools',
  description: 'Everything for the garden',
  status: 'active',
};

export const lighting: Category = {
  id: '7a1d2e3f-4b5c-4d6e-8f90-a1b2c3d4e5f6',
  name: 'Lighting',
  status: 'active',
};

function product(
  id: string,
  name: string,
  amountMinor: number,
  category: Category,
  options: { inStock?: boolean; image?: boolean; description?: string } = {},
): Product {
  const { inStock = true, image = true, description = `A fine ${name.toLowerCase()}.` } = options;
  return {
    id,
    name,
    description,
    price: { amountMinor, currency: 'BRL' },
    categoryId: category.id,
    status: 'active',
    images: image
      ? [
          {
            id: `91d3a0b2-7c4e-4f15-b6a8-${id.slice(-12)}`,
            url: `https://cdn.example.test/${id}.jpg`,
            altText: `${name} on a bench`,
            primary: true,
          },
        ]
      : [],
    availability: { inStock },
    createdAt: '2026-10-02T08:00:00Z',
    updatedAt: '2026-10-02T08:00:00Z',
  };
}

export const rake = product('0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21', 'Rake', 1000, gardenTools);
export const spade = product('1c5f7e2d-3b68-4d94-a021-7e9b4f6c8d32', 'Spade', 2000, gardenTools);
export const hoe = product('2d6a8f3e-4c79-4ea5-b132-8fac5a7d9e43', 'Hoe', 3000, gardenTools, {
  image: false,
});
export const soldOutLamp = product(
  '3e7b9a4f-5d8a-4fb6-8243-9abd6b8eaf54',
  'Sold-out lamp',
  4500,
  lighting,
  { inStock: false, description: 'A lamp everyone wanted.' },
);
export const lantern = product('4f8cab5a-6e9b-4ac7-9354-abce7c9fb065', 'Lantern', 1990, lighting, {
  description: 'Goes well with a rake at dusk.',
});

export const categories: readonly Category[] = [gardenTools, lighting];
export const products: readonly Product[] = [rake, spade, hoe, soldOutLamp, lantern];

export const UNKNOWN_ID = '00000000-0000-4000-8000-000000000000';

export function notFoundProblem(detail: string) {
  return HttpResponse.json(
    {
      type: 'https://ecommerce.example/problems/not-found',
      title: 'Not found',
      status: 404,
      detail,
    },
    { status: 404, headers: { 'Content-Type': PROBLEM } },
  );
}

function matches(item: Product, q: string | null, categoryId: string | null): boolean {
  if (categoryId !== null && item.categoryId !== categoryId) return false;
  if (q === null || q === '') return true;
  const needle = q.toLowerCase();
  return (
    item.name.toLowerCase().includes(needle) ||
    (item.description ?? '').toLowerCase().includes(needle)
  );
}

export function pageOf<T>(items: readonly T[], search: URLSearchParams) {
  const page = Number(search.get('page') ?? '0');
  const size = Number(search.get('size') ?? '20');
  return {
    items: items.slice(page * size, page * size + size),
    page,
    size,
    totalItems: items.length,
  };
}

export const catalogHandlers = [
  http.get(PRODUCTS_URL, ({ request }) => {
    const search = new URL(request.url).searchParams;
    const found = products
      .filter((item) => matches(item, search.get('q'), search.get('categoryId')))
      .sort((left, right) => left.name.localeCompare(right.name));
    return HttpResponse.json(pageOf(found, search));
  }),
  http.get(`${PRODUCTS_URL}/:productId`, ({ params }) => {
    const found = products.find((item) => item.id === params['productId']);
    return found === undefined ? notFoundProblem('Product not found.') : HttpResponse.json(found);
  }),
  http.get(CATEGORIES_URL, ({ request }) =>
    HttpResponse.json(pageOf(categories, new URL(request.url).searchParams)),
  ),
  http.get(`${CATEGORIES_URL}/:categoryId`, ({ params }) => {
    const found = categories.find((item) => item.id === params['categoryId']);
    return found === undefined ? notFoundProblem('Category not found.') : HttpResponse.json(found);
  }),
];
