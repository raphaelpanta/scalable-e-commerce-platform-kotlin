import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import { formatPrice, minorUnits } from '../support/catalogue.ts';
import { transitionOrder } from '../support/operator.ts';
import { randomShopper, type StorefrontWorld } from '../support/world.ts';

// User story 4, following orders: the orders of the scenario's shoppers are placed through the
// API (an approved payment each), an operator advances them through the API, and every assertion
// is on what the shopper sees in the browser.

async function placeFor(world: StorefrontWorld, alias: string, quantity: number): Promise<void> {
  assert.ok(world.shopperToken !== undefined, 'the scenario has no signed-in shopper');
  assert.ok(world.shopperAddressId !== undefined, 'the scenario shopper has no saved address');
  const id = await world.shoppers.placeOrder(
    world.shopperToken,
    world.shopperAddressId,
    world.product(alias).id,
    quantity,
  );
  world.placedOrders.push(id);
}

function lastOrder(world: StorefrontWorld): string {
  const id = world.placedOrders.at(-1);
  assert.ok(id !== undefined, 'the scenario placed no order');
  return id;
}

async function openOrder(world: StorefrontWorld, id: string): Promise<void> {
  await world.goto(`/orders/${id}`);
  await world
    .currentPage()
    .getByRole('heading', { level: 1, name: `Order ${id.slice(0, 8)}` })
    .waitFor({ state: 'visible' });
}

function orderStatus(world: StorefrontWorld) {
  return world
    .currentPage()
    .getByText('Order status', { exact: true })
    .locator('xpath=following-sibling::dd[1]');
}

async function cancelAfterConfirming(world: StorefrontWorld): Promise<void> {
  const page = world.currentPage();
  await page.getByRole('button', { name: 'Cancel order' }).click();
  const dialog = page.getByRole('dialog', { name: 'Cancel this order?' });
  await dialog.waitFor({ state: 'visible' });
  await dialog.getByRole('button', { name: 'Yes, cancel the order' }).click();
  await page.waitForLoadState('networkidle');
}

Given(
  'the shopper has placed {int} paid orders for {string}',
  async function (this: StorefrontWorld, count: number, alias: string) {
    for (let index = 0; index < count; index += 1) await placeFor(this, alias, 1);
  },
);

Given(
  'the shopper has placed a paid order for {string}',
  async function (this: StorefrontWorld, alias: string) {
    await placeFor(this, alias, 1);
  },
);

Given(
  'the shopper has placed a paid order for {int} {string}',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await placeFor(this, alias, quantity);
  },
);

Given(
  'another shopper has placed a paid order for {string}',
  async function (this: StorefrontWorld, alias: string) {
    const other = randomShopper();
    const token = await this.shoppers.registered(other);
    const addressId = await this.shoppers.addAddress(token, 'Porto');
    this.otherShopperOrder = await this.shoppers.placeOrder(
      token,
      addressId,
      this.product(alias).id,
      1,
    );
  },
);

Given('an operator has moved the order to preparing', async function (this: StorefrontWorld) {
  await transitionOrder(this.baseUrl, this.operator, lastOrder(this), 'preparing');
});

Given('the shopper is looking at the order', async function (this: StorefrontWorld) {
  await openOrder(this, lastOrder(this));
});

When('the shopper views their order history', async function (this: StorefrontWorld) {
  await this.goto('/orders');
  await this.currentPage()
    .getByRole('heading', { level: 1, name: 'Your orders' })
    .waitFor({ state: 'visible' });
});

When('the shopper opens the order', async function (this: StorefrontWorld) {
  await openOrder(this, lastOrder(this));
});

When('the shopper starts to cancel the order but keeps it', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByRole('button', { name: 'Cancel order' }).click();
  const dialog = page.getByRole('dialog', { name: 'Cancel this order?' });
  await dialog.waitFor({ state: 'visible' });
  await dialog.getByRole('button', { name: 'Keep the order' }).click();
  await dialog.waitFor({ state: 'hidden' });
});

When('the shopper cancels the order after confirming', async function (this: StorefrontWorld) {
  await cancelAfterConfirming(this);
});

Then(
  "the history lists the shopper's {int} orders, newest first",
  async function (this: StorefrontWorld, count: number) {
    const rows = this.currentPage()
      .getByRole('list', { name: 'Your orders' })
      .getByRole('listitem');
    await rows.first().waitFor({ state: 'visible' });
    const hrefs = await rows
      .getByRole('link')
      .evaluateAll((links) => links.map((link) => link.getAttribute('href')));
    assert.equal(hrefs.length, count);
    assert.deepEqual(
      hrefs,
      [...this.placedOrders].reverse().map((id) => `/orders/${id}`),
    );
  },
);

Then(
  'each order shows its order status, payment status, total and date',
  async function (this: StorefrontWorld) {
    const rows = this.currentPage()
      .getByRole('list', { name: 'Your orders' })
      .getByRole('listitem');
    const texts = await rows.allTextContents();
    assert.ok(texts.length > 0, 'the history is empty');
    for (const text of texts) {
      assert.match(text, /Order: Placed/);
      assert.match(text, /Payment: Approved/);
      assert.match(text, /R\$\s?\d+\.\d\d/);
    }
    const dates = await rows
      .locator('time')
      .evaluateAll((times) => times.map((time) => time.getAttribute('datetime')));
    assert.equal(dates.length, texts.length);
    for (const date of dates)
      assert.ok(!Number.isNaN(Date.parse(date ?? '')), `date ${date ?? ''}`);
  },
);

Then(
  "the other shopper's order is neither listed nor viewable by the shopper",
  async function (this: StorefrontWorld) {
    const other = this.otherShopperOrder;
    assert.ok(other !== undefined, 'no other shopper placed an order');
    const links = await this.currentPage()
      .getByRole('list', { name: 'Your orders' })
      .getByRole('link')
      .evaluateAll((anchors) => anchors.map((anchor) => anchor.getAttribute('href')));
    assert.ok(!links.includes(`/orders/${other}`), 'the other order is listed');
    await this.goto(`/orders/${other}`);
    await this.currentPage()
      .getByRole('heading', { level: 1, name: 'Page not found' })
      .waitFor({ state: 'visible' });
  },
);

Then(
  'the order shows {int} {string} at {float} and the total {float}',
  async function (
    this: StorefrontWorld,
    quantity: number,
    alias: string,
    price: number,
    total: number,
  ) {
    const row = this.currentPage()
      .getByRole('table', { name: 'Items' })
      .getByRole('row', { name: this.product(alias).name });
    await row.waitFor({ state: 'visible' });
    const cells = await row.getByRole('cell').allTextContents();
    assert.equal(cells[0], String(quantity));
    assert.equal(cells[1], formatPrice(minorUnits(price)));
    await this.currentPage()
      .getByText(`Total${formatPrice(minorUnits(total))}`, { exact: true })
      .waitFor({ state: 'visible' });
  },
);

Then(
  'the order shows the delivery address in {string}',
  async function (this: StorefrontWorld, city: string) {
    await this.currentPage()
      .getByText(/^Delivery to /)
      .filter({ hasText: city })
      .waitFor({ state: 'visible' });
  },
);

Then(
  'the order history starts with the order placed by you',
  async function (this: StorefrontWorld) {
    const first = this.currentPage()
      .getByRole('list', { name: 'Status history' })
      .getByRole('listitem')
      .first();
    await first.waitFor({ state: 'visible' });
    const text = (await first.textContent()) ?? '';
    assert.match(text, /Order: Placed/);
    assert.match(text, /by you/);
    assert.equal(await this.currentPage().getByText('by operator').count(), 0);
  },
);

Then('the order is still placed', async function (this: StorefrontWorld) {
  await orderStatus(this).filter({ hasText: 'Placed' }).waitFor({ state: 'visible' });
  assert.equal(await this.currentPage().getByText('The order was cancelled.').count(), 0);
});

Then("the order is cancelled at the shopper's request", async function (this: StorefrontWorld) {
  await orderStatus(this)
    .filter({ hasText: 'Cancelled (cancelled at your request)' })
    .waitFor({ state: 'visible' });
});

Then('no cancel action is offered', async function (this: StorefrontWorld) {
  assert.equal(await this.currentPage().getByRole('button', { name: 'Cancel order' }).count(), 0);
});

Then('the order shows the status {string}', async function (this: StorefrontWorld, status: string) {
  await orderStatus(this).filter({ hasText: status }).waitFor({ state: 'visible' });
});

Then('the cancellation is refused with the reason', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('alert')
    .filter({ hasText: 'Shoppers can only cancel an order while it is placed' })
    .waitFor({ state: 'visible' });
});
