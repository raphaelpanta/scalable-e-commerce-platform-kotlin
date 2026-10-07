import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import { formatPrice, minorUnits } from '../support/catalogue.ts';
import type { StorefrontWorld } from '../support/world.ts';

// User story 2, the cart: every change is made in the browser; fixtures (products, the account
// cart of a registered shopper, an operator's price change) are created through the API.

async function addThroughProductPage(
  world: StorefrontWorld,
  alias: string,
  quantity: number,
  awaitConfirmation: boolean,
): Promise<void> {
  const product = world.product(alias);
  await world.goto(`/products/${product.id}`);
  const page = world.currentPage();
  await page.getByLabel('Quantity').fill(String(quantity));
  await page.getByRole('button', { name: 'Add to cart' }).click();
  if (awaitConfirmation) {
    await page
      .getByRole('status')
      .filter({ hasText: `Added ${String(quantity)} to your cart.` })
      .waitFor({ state: 'visible' });
  }
}

export async function openCart(world: StorefrontWorld): Promise<void> {
  const page = world.currentPage();
  // Like a shopper: follow the header link when already on the storefront (keeps in-page state such as the
  // merge notice); load the cart address only when the browser is elsewhere.
  if (page.url().startsWith(world.baseUrl)) {
    await page
      .getByRole('navigation', { name: 'Primary' })
      .getByRole('link', { name: /^Cart/ })
      .click();
  } else {
    await world.goto('/cart');
  }
  await world
    .currentPage()
    .getByRole('heading', { level: 1, name: 'Your cart' })
    .waitFor({ state: 'visible' });
}

function cartLine(world: StorefrontWorld, alias: string) {
  return world
    .currentPage()
    .getByRole('list', { name: 'Cart lines' })
    .getByRole('listitem', { name: world.product(alias).name, exact: true });
}

Given(
  'a product {string} priced at {float} with {int} units in stock',
  async function (this: StorefrontWorld, alias: string, price: number, stock: number) {
    this.products.set(
      alias,
      await this.catalogue.createProduct(alias, { priceMinor: minorUnits(price), stock }),
    );
  },
);

When(
  'an anonymous shopper adds {int} {string} to the cart',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await addThroughProductPage(this, alias, quantity, true);
  },
);

Given(
  'an anonymous shopper has {int} {string} in the cart',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await addThroughProductPage(this, alias, quantity, true);
  },
);

Given(
  'the shopper has {int} {string} in the cart',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await addThroughProductPage(this, alias, quantity, true);
  },
);

Given(
  'the shopper has {int} {string} and {int} {string} in the cart',
  async function (
    this: StorefrontWorld,
    firstQuantity: number,
    first: string,
    secondQuantity: number,
    second: string,
  ) {
    await addThroughProductPage(this, first, firstQuantity, true);
    await addThroughProductPage(this, second, secondQuantity, true);
  },
);

Given(
  'the shopper, signed out, has an anonymous cart with {int} {string} and {int} {string}',
  async function (
    this: StorefrontWorld,
    firstQuantity: number,
    first: string,
    secondQuantity: number,
    second: string,
  ) {
    // The page never signed in: it is the anonymous shopper of this device.
    await addThroughProductPage(this, first, firstQuantity, true);
    await addThroughProductPage(this, second, secondQuantity, true);
  },
);

When(
  'an anonymous shopper tries to add {int} {string} to the cart',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await addThroughProductPage(this, alias, quantity, false);
  },
);

Then(
  'the quantity is refused because only {int} units are available',
  async function (this: StorefrontWorld, available: number) {
    await this.currentPage()
      .getByRole('alert')
      .filter({ hasText: `Only ${String(available)} units are available` })
      .waitFor({ state: 'visible' });
  },
);

When('the shopper views the cart', async function (this: StorefrontWorld) {
  await openCart(this);
});

Then('the cart has {int} line(s)', async function (this: StorefrontWorld, count: number) {
  await openCart(this);
  const lines = this.currentPage().getByRole('list', { name: 'Cart lines' }).getByRole('listitem');
  await lines.first().waitFor({ state: 'visible' });
  assert.equal(await lines.count(), count);
});

Then('the cart has no lines', async function (this: StorefrontWorld) {
  await openCart(this);
  await this.currentPage()
    .getByRole('heading', { name: 'Your cart is empty' })
    .waitFor({ state: 'visible' });
});

Then(
  'the line for {string} has quantity {int} at a unit price of {float}',
  async function (this: StorefrontWorld, alias: string, quantity: number, price: number) {
    const page = this.currentPage();
    if (!page.url().endsWith('/cart')) await openCart(this);
    const line = cartLine(this, alias);
    await line.waitFor({ state: 'visible' });
    const field = line.getByLabel(`Quantity for ${this.product(alias).name}`);
    assert.equal(await field.inputValue(), String(quantity));
    await line
      .getByText(`Unit price ${formatPrice(minorUnits(price))}`, { exact: true })
      .waitFor({ state: 'visible' });
  },
);

Then('the cart total is {float}', async function (this: StorefrontWorld, total: number) {
  const page = this.currentPage();
  if (!page.url().endsWith('/cart')) await openCart(this);
  await page
    .getByText(`Total${formatPrice(minorUnits(total))}`, { exact: true })
    .waitFor({ state: 'visible' });
});

When(
  'the shopper changes the quantity of {string} to {int}',
  async function (this: StorefrontWorld, alias: string, quantity: number) {
    const page = this.currentPage();
    if (!page.url().endsWith('/cart')) await openCart(this);
    const line = cartLine(this, alias);
    await line.getByLabel(`Quantity for ${this.product(alias).name}`).fill(String(quantity));
    await line.getByRole('button', { name: 'Update' }).click();
    await page.waitForLoadState('networkidle');
  },
);

When(
  'the shopper removes the {string} line',
  async function (this: StorefrontWorld, alias: string) {
    const page = this.currentPage();
    if (!page.url().endsWith('/cart')) await openCart(this);
    await cartLine(this, alias)
      .getByRole('button', { name: `Remove ${this.product(alias).name}` })
      .click();
    await page.waitForLoadState('networkidle');
  },
);

When('the shopper closes and reopens the browser', async function (this: StorefrontWorld) {
  await this.reopen();
});

Given(
  'an operator changes the price of {string} to {float}',
  async function (this: StorefrontWorld, alias: string, price: number) {
    this.products.set(
      alias,
      await this.catalogue.updatePrice(this.product(alias), minorUnits(price)),
    );
  },
);

Then(
  'the line for {string} shows the current unit price of {float}',
  async function (this: StorefrontWorld, alias: string, price: number) {
    await cartLine(this, alias)
      .getByText(`Unit price ${formatPrice(minorUnits(price))}`, { exact: true })
      .waitFor({ state: 'visible' });
  },
);

Then(
  'the line for {string} is flagged as having changed price',
  async function (this: StorefrontWorld, alias: string) {
    await cartLine(this, alias)
      .getByText(/Price changed since you added it/)
      .waitFor({ state: 'visible' });
  },
);

Given(
  'a registered shopper whose account cart holds {int} {string}',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    this.shopperToken = await this.shoppers.registered(this.shopper);
    await this.shoppers.addToAccountCart(this.shopperToken, this.product(alias).id, quantity);
  },
);

Then(
  'the account cart holds {int} {string}',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await openCart(this);
    const line = cartLine(this, alias);
    await line.waitFor({ state: 'visible' });
    const field = line.getByLabel(`Quantity for ${this.product(alias).name}`);
    assert.equal(await field.inputValue(), String(quantity));
  },
);

Then(
  'the account cart holds {int} {string} and {int} {string}',
  async function (
    this: StorefrontWorld,
    firstQuantity: number,
    first: string,
    secondQuantity: number,
    second: string,
  ) {
    await openCart(this);
    for (const [alias, quantity] of [
      [first, firstQuantity],
      [second, secondQuantity],
    ] as const) {
      const line = cartLine(this, alias);
      await line.waitFor({ state: 'visible' });
      const field = line.getByLabel(`Quantity for ${this.product(alias).name}`);
      assert.equal(await field.inputValue(), String(quantity), `quantity of "${alias}"`);
    }
  },
);

Then(
  'the shopper is told that the {string} quantity was capped from {int} to {int}',
  async function (this: StorefrontWorld, alias: string, requested: number, applied: number) {
    await this.currentPage()
      .getByRole('status')
      .filter({ hasText: 'Your cart was merged' })
      .getByText(
        `${this.product(alias).name}: requested ${String(requested)}, kept ${String(applied)}`,
      )
      .waitFor({ state: 'visible' });
  },
);
