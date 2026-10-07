import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import type { StorefrontWorld } from '../support/world.ts';

// User story 6, the operator console: the operator works in the browser; the shopper's order is
// arranged through the API (register, address, cart, checkout with the approving simulated card)
// and read back through the API to see what the shopper would see.

/** The order each scenario's shopper placed through the API. */
const placedOrders = new WeakMap<StorefrontWorld, string>();

function orderIdOf(world: StorefrontWorld): string {
  const id = placedOrders.get(world);
  if (id === undefined) throw new Error('no shopper order was arranged in this scenario');
  return id;
}

const orderNumber = (id: string): string => id.slice(0, 8);

Given(
  'a shopper has a paid order for {string}',
  async function (this: StorefrontWorld, alias: string) {
    const token = await this.shoppers.registered(this.shopper);
    this.shopperToken = token;
    const addressId = await this.shoppers.addAddress(token, 'Lisboa');
    await this.shoppers.addToAccountCart(token, this.product(alias).id, 1);
    placedOrders.set(this, await this.shoppers.placeApprovedOrder(token, addressId));
  },
);

async function openOrderList(world: StorefrontWorld): Promise<void> {
  if (new URL(world.currentPage().url()).pathname !== '/console/orders') {
    await world.goto('/console/orders');
  }
  await world
    .currentPage()
    .getByRole('heading', { level: 1, name: 'Orders' })
    .waitFor({ state: 'visible' });
}

When('the operator opens the order in the console', async function (this: StorefrontWorld) {
  await openOrderList(this);
  const page = this.currentPage();
  await page
    .getByRole('table', { name: 'Orders' })
    .getByRole('link', { name: orderNumber(orderIdOf(this)), exact: true })
    .click();
  await page
    .getByRole('heading', { level: 1, name: `Order ${orderNumber(orderIdOf(this))}` })
    .waitFor({ state: 'visible' });
});

When(
  'the operator chooses {string} and confirms',
  async function (this: StorefrontWorld, action: string) {
    const page = this.currentPage();
    await page
      .getByRole('group', { name: 'Order actions' })
      .getByRole('button', { name: action })
      .click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor({ state: 'visible' });
    await dialog
      .getByRole('button', { name: action === 'Cancel order' ? 'Cancel the order' : action })
      .click();
    await page
      .getByRole('status')
      .filter({ hasText: /^Order \w+ is now / })
      .waitFor({ state: 'visible' });
  },
);

Then(
  'the order is shown as {string} with its payment {string}',
  async function (this: StorefrontWorld, status: string, payment: string) {
    const page = this.currentPage();
    for (const [term, expected] of [
      ['Order status', status],
      ['Payment status', payment],
    ] as const) {
      await page
        .getByText(term, { exact: true })
        .locator('xpath=following-sibling::dd[1]')
        .filter({ hasText: expected })
        .waitFor({ state: 'visible' });
    }
  },
);

Then(
  'the console offers only {string} and {string}',
  async function (this: StorefrontWorld, first: string, second: string) {
    const labels = await this.currentPage()
      .getByRole('group', { name: 'Order actions' })
      .getByRole('button')
      .allTextContents();
    assert.deepEqual(labels, [first, second]);
  },
);

Then(
  "the shopper's own view of the order is {string}",
  async function (this: StorefrontWorld, expected: string) {
    assert.ok(this.shopperToken !== undefined, 'the scenario has no shopper token');
    assert.equal(await this.shoppers.orderStatus(this.shopperToken, orderIdOf(this)), expected);
  },
);

When(
  'the operator filters the orders by {string}',
  async function (this: StorefrontWorld, label: string) {
    await openOrderList(this);
    const page = this.currentPage();
    await page.getByLabel('Filter by status').selectOption({ label });
    await page.waitForLoadState('networkidle');
  },
);

Then(
  'the order list shows the order with status {string} and payment {string}',
  async function (this: StorefrontWorld, status: string, payment: string) {
    const row = this.currentPage()
      .getByRole('table', { name: 'Orders' })
      .getByRole('row')
      .filter({
        has: this.currentPage().getByRole('link', {
          name: orderNumber(orderIdOf(this)),
          exact: true,
        }),
      });
    await row.waitFor({ state: 'visible' });
    const cells = await row.getByRole('cell').allTextContents();
    assert.ok(cells.includes(status), `order status "${status}" in ${JSON.stringify(cells)}`);
    assert.ok(cells.includes(payment), `payment status "${payment}" in ${JSON.stringify(cells)}`);
  },
);

Then('the order list does not show the order', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.waitForLoadState('networkidle');
  await page.getByRole('heading', { level: 1, name: 'Orders' }).waitFor({ state: 'visible' });
  const links = page.getByRole('link', { name: orderNumber(orderIdOf(this)), exact: true });
  assert.equal(await links.count(), 0);
});

async function openStock(world: StorefrontWorld, term: string): Promise<void> {
  await world.goto(`/console/stock?q=${encodeURIComponent(term)}`);
  await world
    .currentPage()
    .getByRole('heading', { level: 1, name: 'Stock' })
    .waitFor({ state: 'visible' });
}

When(
  'the operator adjusts the stock of {string} by {int} because {string}',
  async function (this: StorefrontWorld, alias: string, delta: number, reason: string) {
    const product = this.product(alias);
    await openStock(this, product.name);
    const page = this.currentPage();
    await page.getByRole('button', { name: `Adjust stock of ${product.name}` }).click();
    const form = page.getByRole('form', { name: `Adjust stock of ${product.name}` });
    await form.getByLabel('Change in units').fill(String(delta));
    await form.getByLabel('Reason').fill(reason);
    await form.getByRole('button', { name: 'Apply adjustment' }).click();
  },
);

Then(
  'the console reports the stock of {string} as {int}',
  async function (this: StorefrontWorld, alias: string, units: number) {
    await this.currentPage()
      .getByText(`Stock of ${this.product(alias).name} is now ${String(units)} (was`, {
        exact: false,
      })
      .waitFor({ state: 'visible' });
  },
);

Then('the delta field explains {string}', async function (this: StorefrontWorld, message: string) {
  const field = this.currentPage().getByLabel('Change in units');
  await field.waitFor({ state: 'visible' });
  await this.currentPage().getByText(message, { exact: false }).waitFor({ state: 'visible' });
  assert.equal(await field.getAttribute('aria-invalid'), 'true');
  const describedBy = (await field.getAttribute('aria-describedby')) ?? '';
  assert.ok(describedBy.length > 0, 'the delta field must be described by its error');
});

Then(
  'the primary navigation has no link {string}',
  async function (this: StorefrontWorld, name: string) {
    const links = this.currentPage()
      .getByRole('navigation', { name: 'Primary' })
      .getByRole('link', { name, exact: true });
    assert.equal(await links.count(), 0);
  },
);

Then(
  'the console offers no way to create, edit, withdraw or reinstate a product',
  async function (this: StorefrontWorld) {
    const page = this.currentPage();
    await page.getByRole('table', { name: 'Products' }).waitFor({ state: 'visible' });
    const forbidden = /^(create|new|add|edit|withdraw|reinstate|delete)\b/i;
    assert.equal(await page.getByRole('button', { name: forbidden }).count(), 0);
    assert.equal(await page.getByRole('link', { name: forbidden }).count(), 0);
  },
);

When(
  'the operator opens the stock page searching for {string}',
  async function (this: StorefrontWorld, alias: string) {
    await openStock(this, this.product(alias).name);
    await this.currentPage()
      .getByRole('table', { name: 'Products' })
      .getByRole('row', { name: new RegExp(this.product(alias).name) })
      .waitFor({ state: 'visible' });
  },
);

When(
  'I tab to the adjust button of {string}',
  async function (this: StorefrontWorld, alias: string) {
    const page = this.currentPage();
    const wanted = `Adjust stock of ${this.product(alias).name}`;
    for (let index = 0; index < 60; index += 1) {
      const focused = await page.evaluate(
        () => document.activeElement?.getAttribute('aria-label') ?? '',
      );
      if (focused === wanted) return;
      await page.keyboard.press('Tab');
    }
    assert.fail(`no "${wanted}" button reached by Tab`);
  },
);

When('I type {string}', async function (this: StorefrontWorld, text: string) {
  await this.currentPage().keyboard.type(text);
});

Then('the focus is in the field {string}', async function (this: StorefrontWorld, label: string) {
  const focused = await this.currentPage().evaluate(() => {
    const element = document.activeElement;
    return element instanceof HTMLInputElement
      ? (element.labels?.[0]?.textContent ?? '').trim()
      : '';
  });
  assert.equal(focused, label);
});
