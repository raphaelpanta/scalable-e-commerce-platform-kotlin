import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import type { StorefrontWorld } from '../support/world.ts';

// Feature 007, user story 1: the Vibestore identity while browsing. Steps bind through roles and
// accessible names; nothing here depends on markup or styling.

const BRAND = 'Vibestore';

/** The featured category a scenario chose, per world (scenarios never share one). */
const chosenCategory = new WeakMap<StorefrontWorld, string>();

Given(
  'a category {string} with a product {string} listed without an image',
  async function (this: StorefrontWorld, categoryAlias: string, productAlias: string) {
    const category = await this.catalogue.createCategory(categoryAlias);
    this.categories.set(categoryAlias, category);
    const product = await this.catalogue.createProduct(productAlias, {
      categoryId: category.id,
      image: false,
    });
    this.products.set(productAlias, product);
  },
);

Then(
  'the header shows the Vibestore name linking to the home page',
  async function (this: StorefrontWorld) {
    const brand = this.currentPage().getByRole('banner').getByRole('link', { name: BRAND });
    await brand.waitFor({ state: 'visible' });
    assert.equal(await brand.getAttribute('href'), '/');
  },
);

Then('the tab title names Vibestore', async function (this: StorefrontWorld) {
  assert.match(await this.currentPage().title(), /Vibestore/);
});

When('I choose a featured category', async function (this: StorefrontWorld) {
  const tile = this.currentPage()
    .getByRole('list', { name: 'Featured categories' })
    .getByRole('link')
    .first();
  await tile.waitFor({ state: 'visible' });
  const name = ((await tile.textContent()) ?? '').trim();
  assert.notEqual(name, '', 'the featured category has no name');
  chosenCategory.set(this, name);
  await tile.click();
  await this.currentPage().waitForLoadState('networkidle');
});

Then('I see the products of the chosen category', async function (this: StorefrontWorld) {
  const name = chosenCategory.get(this);
  assert.ok(name !== undefined, 'no featured category was chosen');
  const page = this.currentPage();
  await page.getByRole('heading', { level: 1, name }).waitFor({ state: 'visible' });
  await page.getByRole('list', { name: 'Products' }).waitFor({ state: 'visible' });
});

Then(
  'the product {string} shows the {string} placeholder',
  async function (this: StorefrontWorld, alias: string, placeholder: string) {
    const name = this.product(alias).name;
    await this.currentPage()
      .getByRole('img', { name: `${name} (${placeholder})` })
      .waitFor({ state: 'visible' });
  },
);
