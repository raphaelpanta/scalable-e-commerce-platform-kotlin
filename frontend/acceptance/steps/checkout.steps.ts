import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import { formatPrice, minorUnits } from '../support/catalogue.ts';
import type { StorefrontWorld } from '../support/world.ts';

// User story 2, the checkout: the three steps, the confirmation and every refusal, driven in the
// browser; the operator's price and stock changes go through the API as the operator.

const METHOD_LABELS: Readonly<Record<string, string>> = {
  'the simulated card that is approved': 'Simulated card that is approved',
  'the simulated card that is declined': 'Simulated card that is declined',
  'the simulated card with an unreachable provider': 'Simulated card with an unreachable provider',
};

function methodLabel(method: string): string {
  const label = METHOD_LABELS[method];
  if (label === undefined) throw new Error(`unknown payment method "${method}"`);
  return label;
}

/** Address and payment steps up to the review step, without confirming. */
async function reviewCheckout(world: StorefrontWorld, method: string): Promise<void> {
  await world.goto('/checkout');
  const page = world.currentPage();
  await page.getByRole('heading', { level: 1, name: 'Checkout' }).waitFor({ state: 'visible' });
  await page.getByRole('radio', { name: /Home/ }).check();
  await page.getByRole('button', { name: 'Continue to payment' }).click();
  await page.getByRole('radio', { name: methodLabel(method) }).check();
  await page.getByRole('button', { name: 'Continue to review' }).click();
  await page.getByRole('table', { name: 'Items in your order' }).waitFor({ state: 'visible' });
  await page.waitForLoadState('networkidle');
}

async function confirmOrder(world: StorefrontWorld): Promise<void> {
  const page = world.currentPage();
  const confirm = page.getByRole('button', { name: 'Confirm order' });
  await confirm.waitFor({ state: 'visible' });
  await confirm.click();
  await page.waitForLoadState('networkidle');
}

Given(
  'the shopper has reviewed the checkout paying with {}',
  async function (this: StorefrontWorld, method: string) {
    await reviewCheckout(this, method);
  },
);

When(
  'the shopper checks out paying with {}',
  async function (this: StorefrontWorld, method: string) {
    await reviewCheckout(this, method);
    await confirmOrder(this);
  },
);

When('the shopper opens the checkout', async function (this: StorefrontWorld) {
  await this.goto('/checkout');
  await this.currentPage()
    .getByRole('heading', { level: 1, name: 'Checkout' })
    .waitFor({ state: 'visible' });
});

When('the shopper confirms the order', async function (this: StorefrontWorld) {
  await confirmOrder(this);
});

When(
  'the shopper accepts the new prices and confirms again',
  async function (this: StorefrontWorld) {
    await this.currentPage().getByRole('button', { name: 'Accept the new prices' }).click();
    await confirmOrder(this);
  },
);

When('the shopper presses {string} twice', async function (this: StorefrontWorld, name: string) {
  const page = this.currentPage();
  await page.getByRole('button', { name }).dblclick();
  await page.waitForLoadState('networkidle');
});

Given(
  'an operator removes all stock of {string}',
  async function (this: StorefrontWorld, alias: string) {
    await this.catalogue.removeStock(this.product(alias));
  },
);

Then(
  'the order is placed with its payment {word}',
  async function (this: StorefrontWorld, payment: string) {
    const page = this.currentPage();
    const heading = payment === 'pending' ? 'Order received' : 'Order confirmed';
    await page.getByRole('heading', { level: 1, name: heading }).waitFor({ state: 'visible' });
    assert.match(new URL(page.url()).pathname, /^\/orders\/[0-9a-f-]{36}\/confirmation$/);
    const status = payment === 'pending' ? 'Awaiting payment' : 'Approved';
    await page
      .getByText('Payment status', { exact: true })
      .locator('xpath=following-sibling::dd[1]')
      .filter({ hasText: status })
      .waitFor({ state: 'visible' });
    await page
      .getByText('Order status', { exact: true })
      .locator('xpath=following-sibling::dd[1]')
      .filter({ hasText: 'Placed' })
      .waitFor({ state: 'visible' });
  },
);

Then(
  'the confirmation shows the order number, {int} {string} at {float} and {int} {string} at {float}',
  async function (
    this: StorefrontWorld,
    firstQuantity: number,
    first: string,
    firstPrice: number,
    secondQuantity: number,
    second: string,
    secondPrice: number,
  ) {
    const page = this.currentPage();
    const orderId = new URL(page.url()).pathname.split('/')[2] ?? '';
    await page
      .getByText(`Order number ${orderId.slice(0, 8)}`, { exact: true })
      .waitFor({ state: 'visible' });
    await page.getByText(orderId, { exact: true }).waitFor({ state: 'visible' });
    const table = page.getByRole('table', { name: 'Items' });
    for (const [alias, quantity, price] of [
      [first, firstQuantity, firstPrice],
      [second, secondQuantity, secondPrice],
    ] as const) {
      const row = table.getByRole('row', { name: this.product(alias).name });
      await row.waitFor({ state: 'visible' });
      const cells = await row.getByRole('cell').allTextContents();
      assert.equal(cells[0], String(quantity), `quantity of "${alias}"`);
      assert.equal(cells[1], formatPrice(minorUnits(price)), `unit price of "${alias}"`);
    }
  },
);

Then('the order total is {float}', async function (this: StorefrontWorld, total: number) {
  await this.currentPage()
    .getByText(`Total${formatPrice(minorUnits(total))}`, { exact: true })
    .waitFor({ state: 'visible' });
});

Then("the shopper's cart is empty", async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByLabel('0 items in cart').waitFor({ state: 'visible' });
  await this.goto('/cart');
  await page.getByRole('heading', { name: 'Your cart is empty' }).waitFor({ state: 'visible' });
});

Then(
  'the checkout is refused because the price of {string} changed from {float} to {float}',
  async function (this: StorefrontWorld, alias: string, oldPrice: number, newPrice: number) {
    const notice = this.currentPage().getByRole('alert').filter({ hasText: 'Prices changed' });
    await notice.waitFor({ state: 'visible' });
    await notice
      .getByText(
        `${this.product(alias).name}: was ${formatPrice(minorUnits(oldPrice))}, now ${formatPrice(minorUnits(newPrice))}`,
      )
      .waitFor({ state: 'visible' });
    assert.equal(
      await this.currentPage().getByRole('button', { name: 'Confirm order' }).isDisabled(),
      true,
      'confirming must wait for the explicit acceptance',
    );
  },
);

Then(
  'the checkout is refused because the stock is insufficient for {string}',
  async function (this: StorefrontWorld, alias: string) {
    const notice = this.currentPage()
      .getByRole('alert')
      .filter({ hasText: 'Some items are no longer available' });
    await notice.waitFor({ state: 'visible' });
    await notice
      .getByText(new RegExp(`^${this.product(alias).name}: requested`))
      .waitFor({ state: 'visible' });
  },
);

Then('the shopper can adjust the cart', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('link', { name: 'Adjust your cart' })
    .waitFor({ state: 'visible' });
});

Then(
  'the checkout is refused because the payment was declined',
  async function (this: StorefrontWorld) {
    const notice = this.currentPage()
      .getByRole('alert')
      .filter({ hasText: 'Your payment was declined' });
    await notice.waitFor({ state: 'visible' });
    await notice.getByText(/your cart is kept/).waitFor({ state: 'visible' });
  },
);

Then(
  'the cart still holds {int} {string}',
  async function (this: StorefrontWorld, quantity: number, alias: string) {
    await this.goto('/cart');
    const line = this.currentPage()
      .getByRole('list', { name: 'Cart lines' })
      .getByRole('listitem', { name: this.product(alias).name, exact: true });
    await line.waitFor({ state: 'visible' });
    const field = line.getByLabel(`Quantity for ${this.product(alias).name}`);
    assert.equal(await field.inputValue(), String(quantity));
  },
);

Then(
  'the confirmation shows the time left before the payment window ends',
  async function (this: StorefrontWorld) {
    const timer = this.currentPage().getByRole('timer');
    await timer.waitFor({ state: 'visible' });
    assert.match(
      (await timer.textContent()) ?? '',
      /Awaiting payment: \d+:\d\d left before the payment window ends\./,
    );
  },
);

Then(
  'the shopper has exactly {int} order(s)',
  async function (this: StorefrontWorld, count: number) {
    assert.ok(this.shopperToken !== undefined, 'the scenario has no shopper token');
    const orders = await this.shoppers.orders(this.shopperToken);
    assert.equal(orders.length, count);
  },
);

Then(
  'the shopper is back on the review step with the chosen address and payment method',
  async function (this: StorefrontWorld) {
    const page = this.currentPage();
    await page.getByRole('table', { name: 'Items in your order' }).waitFor({ state: 'visible' });
    const url = new URL(page.url());
    assert.equal(url.pathname, '/checkout');
    assert.equal(url.searchParams.get('step'), 'review');
    await page
      .getByText('Deliver to', { exact: true })
      .locator('xpath=following-sibling::dd[1]')
      .filter({ hasText: 'Ana Silva' })
      .waitFor({ state: 'visible' });
    await page
      .getByText('Pay with', { exact: true })
      .locator('xpath=following-sibling::dd[1]')
      .filter({ hasText: 'Simulated card that is approved' })
      .waitFor({ state: 'visible' });
    const confirm = page.getByRole('button', { name: 'Confirm order' });
    await confirm.waitFor({ state: 'visible' });
    assert.equal(await confirm.isEnabled(), true);
  },
);

/** Tabs to a radio option by the text of its label (an input has no text of its own). */
When('I tab to the option {string}', async function (this: StorefrontWorld, label: string) {
  const page = this.currentPage();
  for (let index = 0; index < 60; index += 1) {
    const focused = await page.evaluate(() => {
      const element = document.activeElement;
      if (!(element instanceof HTMLInputElement)) return '';
      return (element.labels?.[0]?.textContent ?? '').trim();
    });
    if (focused.startsWith(label)) return;
    await page.keyboard.press('Tab');
  }
  assert.fail(`no option labelled "${label}" reached by Tab`);
});
