import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

import { type DataTable, Given, Then, When } from '@cucumber/cucumber';

import { DEFAULT_PRICE_MINOR, newSearchTerm, suffix } from '../support/catalogue.ts';
import type { StorefrontWorld } from '../support/world.ts';

// User story 1: browsing the storefront anonymously. Fixtures are created through the API as the
// operator (support/catalogue.ts); every assertion is on what the shopper sees in the browser.

function formatPrice(priceMinor: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'BRL' }).format(
    priceMinor / 100,
  );
}

function categoryPath(world: StorefrontWorld, alias: string, search = ''): string {
  return `/categories/${world.category(alias).id}${search}`;
}

Given('the seeded catalogue', async function (this: StorefrontWorld) {
  await this.catalogue.assertSeeded();
});

Given(
  'a category {string} with these products in stock:',
  async function (this: StorefrontWorld, alias: string, products: DataTable) {
    const category = await this.catalogue.createCategory(alias);
    this.categories.set(alias, category);
    const names = products.raw().map((row) => row[0] ?? '');
    for (const [index, name] of names.entries()) {
      const product = await this.catalogue.createProduct(name, {
        categoryId: category.id,
        priceMinor: DEFAULT_PRICE_MINOR + index * DEFAULT_PRICE_MINOR,
      });
      this.products.set(name, product);
    }
  },
);

Given('a product {string} with no stock', async function (this: StorefrontWorld, alias: string) {
  this.products.set(alias, await this.catalogue.createProduct(alias, { stock: 0 }));
});

Given(
  'a product {string} named after a new search term',
  async function (this: StorefrontWorld, alias: string) {
    const term = newSearchTerm();
    this.searchTerm = term;
    const product = await this.catalogue.createProduct(alias, {
      name: `${term} ${alias} ${term}`,
      description: `The original ${term}.`,
    });
    this.products.set(alias, product);
  },
);

Given(
  'a product {string} that mentions the search term only in its description',
  async function (this: StorefrontWorld, alias: string) {
    const term = this.searchTerm ?? newSearchTerm();
    this.searchTerm = term;
    const product = await this.catalogue.createProduct(alias, {
      name: `${alias} ${suffix()}`,
      description: `Goes well with a ${term}.`,
    });
    this.products.set(alias, product);
  },
);

Given(
  'a product {string} named after the search term that an operator has withdrawn',
  async function (this: StorefrontWorld, alias: string) {
    const term = this.searchTerm ?? newSearchTerm();
    this.searchTerm = term;
    const product = await this.catalogue.createProduct(alias, {
      name: `${term} ${alias}`,
      description: `A former ${term}.`,
    });
    this.products.set(alias, product);
    await this.catalogue.withdraw(product.id);
  },
);

When(
  'an anonymous shopper opens the category {string}',
  async function (this: StorefrontWorld, alias: string) {
    await this.goto(categoryPath(this, alias));
  },
);

When(
  'an anonymous shopper opens the category {string} two per page',
  async function (this: StorefrontWorld, alias: string) {
    await this.goto(categoryPath(this, alias, '?size=2'));
  },
);

When(
  'an anonymous shopper opens the second page of the category {string} two per page',
  async function (this: StorefrontWorld, alias: string) {
    await this.goto(categoryPath(this, alias, '?size=2&page=1'));
  },
);

When(
  'an anonymous shopper views the product {string}',
  async function (this: StorefrontWorld, alias: string) {
    await this.goto(`/products/${this.product(alias).id}`);
  },
);

When('an anonymous shopper searches for the term', async function (this: StorefrontWorld) {
  assert.ok(this.searchTerm !== undefined, 'no search term was coined in this scenario');
  await this.goto('/search');
  const page = this.currentPage();
  await page.getByLabel('Search products').fill(this.searchTerm);
  await page.getByRole('button', { name: 'Search' }).click();
  await page.waitForLoadState('networkidle');
});

When(
  'an anonymous shopper searches for a term nothing matches',
  async function (this: StorefrontWorld) {
    this.searchTerm = newSearchTerm();
    await this.goto(`/search?q=${encodeURIComponent(this.searchTerm)}`);
  },
);

When(
  'an anonymous shopper follows a link to a product that does not exist',
  async function (this: StorefrontWorld) {
    await this.goto(`/products/${randomUUID()}`);
  },
);

When('I tab to the product {string}', async function (this: StorefrontWorld, alias: string) {
  const name = this.product(alias).name;
  const page = this.currentPage();
  // A keyboard user skips the banner and the category navigation with the skip link first.
  await page.keyboard.press('Tab');
  const skipLink = page.getByRole('link', { name: /skip to/i });
  if (await skipLink.evaluate((el) => el === document.activeElement).catch(() => false)) {
    await page.keyboard.press('Enter');
  }
  for (let index = 0; index < 200; index += 1) {
    const focused = await page.evaluate(() => document.activeElement?.textContent.trim() ?? '');
    if (focused === name) return;
    await page.keyboard.press('Tab');
  }
  assert.fail(`no focusable element named "${name}" reached by Tab`);
});

Then(
  'the page shows {int} product(s) out of {int} in total',
  async function (this: StorefrontWorld, shown: number, total: number) {
    const page = this.currentPage();
    await page
      .getByText(`Showing ${shown} of ${total} ${total === 1 ? 'product' : 'products'}`, {
        exact: true,
      })
      .waitFor({ state: 'visible' });
    const items = page.getByRole('list', { name: 'Products' }).getByRole('listitem');
    assert.equal(await items.count(), shown);
  },
);

Then(
  'every listed product belongs to category {string} and shows its name, price, primary image and availability',
  async function (this: StorefrontWorld, alias: string) {
    const category = this.category(alias);
    const expected = [...this.products.values()].filter((p) => p.categoryId === category.id);
    const items = this.currentPage().getByRole('list', { name: 'Products' }).getByRole('listitem');
    const count = await items.count();
    for (let index = 0; index < count; index += 1) {
      const item = items.nth(index);
      const name = ((await item.getByRole('link').textContent()) ?? '').trim();
      const product = expected.find((p) => p.name === name);
      assert.ok(product !== undefined, `"${name}" is not a product of category "${alias}"`);
      await item.getByText(formatPrice(product.priceMinor), { exact: true }).waitFor();
      const image = item.getByRole('img', { name: product.alias, exact: true });
      assert.equal(await image.getAttribute('src'), `https://cdn.example.test/${product.id}.jpg`);
      await item.getByText(/^(In stock|Out of stock)$/).waitFor();
    }
  },
);

Then(
  'the address carries the category {string} and two per page',
  function (this: StorefrontWorld, alias: string) {
    const url = new URL(this.currentPage().url());
    assert.ok(url.pathname.endsWith(this.category(alias).id), `path ${url.pathname}`);
    assert.equal(url.searchParams.get('size'), '2');
  },
);

Then('the address carries the second page', function (this: StorefrontWorld) {
  const url = new URL(this.currentPage().url());
  assert.equal(url.searchParams.get('page'), '1');
});

Then('the address carries the search term', function (this: StorefrontWorld) {
  const url = new URL(this.currentPage().url());
  assert.equal(url.pathname, '/search');
  assert.equal(url.searchParams.get('q'), this.searchTerm);
});

Then(
  'the category {string} is marked as the current one',
  async function (this: StorefrontWorld, alias: string) {
    const link = this.currentPage()
      .getByRole('navigation', { name: 'Categories' })
      .getByRole('link', { name: this.category(alias).name, exact: true });
    assert.equal(await link.getAttribute('aria-current'), 'page');
  },
);

Then(
  'the product page shows the name, description, price, primary image and availability of {string}',
  async function (this: StorefrontWorld, alias: string) {
    const product = this.product(alias);
    const page = this.currentPage();
    await page.getByRole('heading', { level: 1, name: product.name }).waitFor({ state: 'visible' });
    await page.getByText(product.description, { exact: true }).waitFor();
    await page.getByText(formatPrice(product.priceMinor), { exact: true }).waitFor();
    const image = page.getByRole('img', { name: product.alias, exact: true });
    assert.equal(await image.getAttribute('src'), `https://cdn.example.test/${product.id}.jpg`);
    await page.getByText(/^(In stock|Out of stock)$/).waitFor();
  },
);

Then('it is shown as in stock', async function (this: StorefrontWorld) {
  await this.currentPage().getByText('In stock', { exact: true }).waitFor({ state: 'visible' });
});

Then('it is shown as out of stock', async function (this: StorefrontWorld) {
  await this.currentPage().getByText('Out of stock', { exact: true }).waitFor({ state: 'visible' });
});

Then('the product can be added to the cart', async function (this: StorefrontWorld) {
  const button = this.currentPage().getByRole('button', { name: 'Add to cart' });
  assert.equal(await button.isEnabled(), true, '"Add to cart" must be enabled');
});

Then('the product cannot be added to the cart', async function (this: StorefrontWorld) {
  const button = this.currentPage().getByRole('button', { name: 'Add to cart' });
  assert.equal(await button.isDisabled(), true, '"Add to cart" must be disabled');
});

Then('the shopper is told why the product cannot be added', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByText('This product is out of stock and cannot be added to the cart.')
    .waitFor({ state: 'visible' });
});

Then(
  'the products {string} and {string} are found in that order',
  async function (this: StorefrontWorld, first: string, second: string) {
    const items = this.currentPage().getByRole('list', { name: 'Products' }).getByRole('listitem');
    await items.first().waitFor();
    const names = (await items.getByRole('link').allTextContents()).map((name) => name.trim());
    const indexOfFirst = names.indexOf(this.product(first).name);
    const indexOfSecond = names.indexOf(this.product(second).name);
    assert.ok(indexOfFirst >= 0, `"${first}" was not found among ${names.join(', ')}`);
    assert.ok(indexOfSecond >= 0, `"${second}" was not found among ${names.join(', ')}`);
    assert.ok(indexOfFirst < indexOfSecond, `"${first}" must rank before "${second}"`);
  },
);

Then('the product {string} is not found', async function (this: StorefrontWorld, alias: string) {
  const matches = await this.currentPage()
    .getByRole('link', { name: this.product(alias).name, exact: true })
    .count();
  assert.equal(matches, 0, `"${alias}" must not be listed`);
});

Then('the shopper is told nothing was found', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('heading', { name: /^Nothing found for/ })
    .waitFor({ state: 'visible' });
});

Then('the shopper is offered to browse all products', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('link', { name: 'Browse all products' })
    .waitFor({ state: 'visible' });
});

Then('the shopper is offered a way back to browsing', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('link', { name: 'Browse products' })
    .waitFor({ state: 'visible' });
});
