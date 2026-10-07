import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { renderApp } from './render.tsx';
import {
  CATEGORIES_URL,
  gardenTools,
  hoe,
  lantern,
  lighting,
  notFoundProblem,
  PROBLEM,
  PRODUCTS_URL,
  rake,
  soldOutLamp,
  spade,
  UNKNOWN_ID,
} from '../msw/catalog.ts';
import { server } from '../msw/server.ts';

/** Every request URL MSW sees from now on (listener removed after each test). */
function recordRequests(): string[] {
  const urls: string[] = [];
  server.events.on('request:start', ({ request }) => {
    urls.push(request.url);
  });
  return urls;
}

function productNames(): string[] {
  const list = screen.getByRole('list', { name: 'Products' });
  return within(list)
    .getAllByRole('listitem')
    .map((item) => within(item).getByRole('link').textContent);
}

afterEach(() => {
  server.events.removeAllListeners();
});

describe('Home page (FR-001)', () => {
  it('lists name, formatted price, primary image, availability and the categories', async () => {
    const urls = recordRequests();
    renderApp('/');
    expect(await screen.findByRole('heading', { level: 1, name: 'Products' })).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Products' });
    expect(productNames()).toEqual(['Hoe', 'Lantern', 'Rake', 'Sold-out lamp', 'Spade']);
    const rakeCard = within(list).getByRole('link', { name: 'Rake' }).closest('li');
    expect(rakeCard).not.toBeNull();
    expect(within(rakeCard!).getByText('R$10.00')).toBeInTheDocument();
    expect(within(rakeCard!).getByRole('img', { name: 'Rake on a bench' })).toHaveAttribute(
      'src',
      `https://cdn.example.test/${rake.id}.jpg`,
    );
    expect(within(rakeCard!).getByText('In stock')).toBeInTheDocument();
    expect(within(list).getByRole('link', { name: 'Rake' })).toHaveAttribute(
      'href',
      `/products/${rake.id}`,
    );
    const lampCard = within(list).getByRole('link', { name: 'Sold-out lamp' }).closest('li');
    expect(within(lampCard!).getByText('Out of stock')).toBeInTheDocument();
    const hoeCard = within(list).getByRole('link', { name: 'Hoe' }).closest('li');
    expect(
      within(hoeCard!).getByRole('img', { name: 'Hoe (no image available)' }),
    ).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Categories' });
    expect(within(nav).getByRole('link', { name: 'Garden tools' })).toHaveAttribute(
      'href',
      `/categories/garden-tools-${gardenTools.id}`,
    );
    expect(within(nav).getByRole('link', { name: 'Lighting' })).toBeInTheDocument();
    expect(screen.getByText('Showing 5 of 5 products')).toBeInTheDocument();
    expect(urls.find((url) => url.startsWith(PRODUCTS_URL))).toBe(PRODUCTS_URL);
  });

  it('shows the empty state with one next action when the catalogue has no products', async () => {
    server.use(
      http.get(PRODUCTS_URL, () =>
        HttpResponse.json({ items: [], page: 0, size: 20, totalItems: 0 }),
      ),
    );
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'No products yet' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Search the store' })).toHaveAttribute(
      'href',
      '/search',
    );
  });
});

describe('Category page (FR-001, FR-003)', () => {
  it("shows only that category's products with a pager and keeps page and size in the URL", async () => {
    const user = userEvent.setup();
    const urls = recordRequests();
    const { router } = renderApp(`/categories/${gardenTools.id}?size=2`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Garden tools' }),
    ).toBeInTheDocument();
    await screen.findByRole('list', { name: 'Products' });
    expect(productNames()).toEqual(['Hoe', 'Rake']);
    expect(screen.getByText('Showing 2 of 3 products')).toBeInTheDocument();
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    const firstRequest = new URL(urls.find((url) => url.startsWith(PRODUCTS_URL))!);
    expect(firstRequest.searchParams.get('categoryId')).toBe(gardenTools.id);
    expect(firstRequest.searchParams.get('size')).toBe('2');
    expect(firstRequest.searchParams.has('page')).toBe(false);
    expect(firstRequest.searchParams.has('q')).toBe(false);

    const pagination = screen.getByRole('navigation', { name: 'Pagination' });
    expect(within(pagination).queryByRole('link', { name: 'Previous page' })).toBeNull();
    await user.click(within(pagination).getByRole('link', { name: 'Next page' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?size=2&page=1');
    });
    expect(router.state.location.pathname).toBe(`/categories/${gardenTools.id}`);
    await waitFor(() => {
      expect(productNames()).toEqual(['Spade']);
    });
    expect(screen.getByText('Showing 1 of 3 products')).toBeInTheDocument();
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    const secondRequest = new URL(urls.filter((url) => url.startsWith(PRODUCTS_URL)).at(-1)!);
    expect(secondRequest.searchParams.get('page')).toBe('1');
    expect(secondRequest.searchParams.get('size')).toBe('2');
    // The pager was remounted around the loading state: query it again.
    const secondPager = screen.getByRole('navigation', { name: 'Pagination' });
    expect(within(secondPager).queryByRole('link', { name: 'Next page' })).toBeNull();
    expect(within(secondPager).getByRole('link', { name: 'Previous page' })).toHaveAttribute(
      'href',
      `/categories/${gardenTools.id}?size=2`,
    );
    const nav = screen.getByRole('navigation', { name: 'Categories' });
    expect(within(nav).getByRole('link', { name: 'Garden tools' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('accepts and ignores a readable slug before the id', async () => {
    renderApp(`/categories/garden-tools-${gardenTools.id}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Garden tools' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Everything for the garden')).toBeInTheDocument();
  });

  it('renders the not-found page for an unknown or withdrawn category', async () => {
    renderApp(`/categories/${UNKNOWN_ID}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
  });

  it('renders the not-found page for an invalid id without any request', async () => {
    const urls = recordRequests();
    renderApp('/categories/garden-tools');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(urls.filter((url) => url.startsWith(CATEGORIES_URL) && url !== CATEGORIES_URL)).toEqual(
      [],
    );
    expect(urls.filter((url) => url.startsWith(PRODUCTS_URL))).toEqual([]);
  });

  it('offers a way back when the page is past the end of the list', async () => {
    renderApp(`/categories/${gardenTools.id}?size=2&page=7`);
    expect(await screen.findByRole('heading', { name: 'No more products' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the first page' })).toHaveAttribute(
      'href',
      `/categories/${gardenTools.id}?size=2`,
    );
  });
});

describe('Search page (FR-001, FR-003)', () => {
  it('renders the results in the order the platform returns them', async () => {
    server.use(
      http.get(PRODUCTS_URL, ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        if (q !== 'lamp') return undefined;
        return HttpResponse.json({
          items: [soldOutLamp, lantern, rake],
          page: 0,
          size: 20,
          totalItems: 3,
        });
      }),
    );
    renderApp('/search?q=lamp');
    await screen.findByRole('list', { name: 'Products' });
    expect(productNames()).toEqual(['Sold-out lamp', 'Lantern', 'Rake']);
    expect(screen.getByText('Results for “lamp”')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Search products' })).toHaveValue('lamp');
  });

  it('shows a clear empty state when nothing matches', async () => {
    renderApp('/search?q=zzz');
    expect(
      await screen.findByRole('heading', { name: 'Nothing found for “zzz”' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse all products' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('list', { name: 'Products' })).toBeNull();
  });

  it('sends q trimmed and capped at 100 characters', async () => {
    const urls = recordRequests();
    const long = `   ${'a'.repeat(150)}   `;
    renderApp(`/search?q=${encodeURIComponent(long)}`);
    await screen.findByRole('heading', { name: `Nothing found for “${'a'.repeat(100)}”` });
    const request = new URL(urls.find((url) => url.startsWith(PRODUCTS_URL))!);
    expect(request.searchParams.get('q')).toBe('a'.repeat(100));
  });

  it('shows all products with a prompt when q is empty, and searches from the box', async () => {
    const user = userEvent.setup();
    const urls = recordRequests();
    const { router } = renderApp('/search');
    expect(await screen.findByRole('heading', { level: 1, name: 'Search' })).toBeInTheDocument();
    expect(
      screen.getByText('Type a product name to search. Showing all products.'),
    ).toBeInTheDocument();
    await screen.findByRole('list', { name: 'Products' });
    expect(productNames()).toHaveLength(5);
    expect(new URL(urls.find((url) => url.startsWith(PRODUCTS_URL))!).searchParams.has('q')).toBe(
      false,
    );
    const box = screen.getByRole('searchbox', { name: 'Search products' });
    await user.type(box, '  rake  ');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => {
      expect(router.state.location.search).toBe('?q=rake');
    });
    await waitFor(() => {
      expect(productNames()).toEqual(['Lantern', 'Rake']);
    });
    expect(screen.getByText('Results for “rake”')).toBeInTheDocument();
  });

  it('renders the search term as text, never as markup', async () => {
    renderApp(`/search?q=${encodeURIComponent('<b>bold</b>')}`);
    expect(
      await screen.findByRole('heading', { name: 'Nothing found for “<b>bold</b>”' }),
    ).toBeInTheDocument();
    expect(document.querySelector('b')).toBeNull();
  });
});

describe('Product page (FR-002)', () => {
  it('shows description, image, price and availability and allows adding when in stock', async () => {
    renderApp(`/products/${rake.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Rake' })).toBeInTheDocument();
    expect(screen.getByText('A fine rake.')).toBeInTheDocument();
    expect(screen.getByText('R$10.00')).toBeInTheDocument();
    expect(screen.getByText('In stock')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Rake on a bench' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add to cart' })).toBeEnabled();
  });

  it('disables "Add to cart" with an explanation when out of stock', async () => {
    renderApp(`/products/${soldOutLamp.id}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Sold-out lamp' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Out of stock')).toBeInTheDocument();
    const button = screen.getByRole('button', { name: 'Add to cart' });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(
      'This product is out of stock and cannot be added to the cart.',
    );
  });

  it('shows the neutral placeholder when the image fails to load', async () => {
    renderApp(`/products/${spade.id}`);
    const image = await screen.findByRole('img', { name: 'Spade on a bench' });
    act(() => {
      image.dispatchEvent(new Event('error'));
    });
    expect(
      screen.getByRole('img', { name: 'Spade on a bench (no image available)' }),
    ).toBeInTheDocument();
  });

  it('renders the not-found page for an unknown or withdrawn product', async () => {
    renderApp(`/products/${UNKNOWN_ID}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Browse products' })).toHaveAttribute('href', '/');
  });

  it('renders the not-found page for an invalid id without any request', async () => {
    const urls = recordRequests();
    renderApp('/products/not-a-uuid');
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(urls).toEqual([]);
  });
});

describe('States every route must render (FR-016)', () => {
  it('shows a loading status while the request is in flight', async () => {
    server.use(
      http.get(`${PRODUCTS_URL}/:productId`, async () => {
        await delay('infinite');
        return undefined;
      }),
    );
    renderApp(`/products/${hoe.id}`);
    expect(await screen.findByRole('status')).toHaveTextContent('Loading product…');
  });

  it('shows a readable error with retry and the correlation id, then recovers', async () => {
    const user = userEvent.setup();
    server.use(
      http.get(
        PRODUCTS_URL,
        () =>
          HttpResponse.json(
            {
              type: 'https://ecommerce.example/problems/validation',
              title: 'Bad request',
              status: 400,
              detail: 'Query parameter size must be at most 100.',
            },
            { status: 400, headers: { 'Content-Type': PROBLEM, 'X-Correlation-Id': 'corr-42' } },
          ),
        { once: true },
      ),
    );
    renderApp('/');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Query parameter size must be at most 100.');
    expect(alert).not.toHaveTextContent('"type"');
    await user.click(screen.getByText('Support details'));
    expect(screen.getByText('corr-42')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('list', { name: 'Products' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows the throttled countdown with retry disabled on a 429', async () => {
    server.use(
      http.get(`${PRODUCTS_URL}/:productId`, () =>
        HttpResponse.json(
          {
            type: 'https://ecommerce.example/problems/throttled',
            title: 'Too many requests',
            status: 429,
          },
          { status: 429, headers: { 'Content-Type': PROBLEM, 'Retry-After': '7' } },
        ),
      ),
    );
    renderApp(`/products/${rake.id}`);
    expect(await screen.findByRole('heading', { name: 'Too many requests' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Try again in 7 seconds.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled();
  });

  it('shows the categories navigation error without hiding the products', async () => {
    server.use(
      http.get(CATEGORIES_URL, () =>
        HttpResponse.json(
          {
            type: 'https://ecommerce.example/problems/validation',
            title: 'Bad request',
            status: 400,
          },
          { status: 400, headers: { 'Content-Type': PROBLEM } },
        ),
      ),
    );
    renderApp('/');
    expect(await screen.findByRole('list', { name: 'Products' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Categories' });
    expect(await within(nav).findByRole('alert')).toHaveTextContent('Bad request');
  });

  it('uses the not-found problem of a category 404 only for the detail, never as an error', async () => {
    server.use(
      http.get(`${CATEGORIES_URL}/:categoryId`, () => notFoundProblem('Category not found.')),
    );
    renderApp(`/categories/${lighting.id}`);
    expect(
      await screen.findByRole('heading', { level: 1, name: 'Page not found' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
