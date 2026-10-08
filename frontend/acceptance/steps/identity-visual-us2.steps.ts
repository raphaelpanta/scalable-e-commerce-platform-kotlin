import assert from 'node:assert/strict';

import { Then } from '@cucumber/cucumber';

import type { StorefrontWorld } from '../support/world.ts';

// Vibestore visual identity, user story 2: the branded empty and not-found states. Steps bind to
// roles and accessible names only, never to styling.

Then(
  'the empty cart offers a single way back to the products',
  async function (this: StorefrontWorld) {
    const page = this.currentPage();
    await page.getByRole('heading', { name: 'Your cart is empty' }).waitFor({ state: 'visible' });
    const main = page.getByRole('main');
    const actions = main.getByRole('link', { name: 'Browse products' });
    assert.equal(await actions.count(), 1);
    assert.equal(await actions.first().getAttribute('href'), '/');
    assert.equal(await main.getByRole('button').count(), 0);
  },
);

Then('the not-found page offers a way back to the store', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page
    .getByRole('heading', { level: 1, name: 'Page not found' })
    .waitFor({ state: 'visible' });
  const home = page.getByRole('main').getByRole('link', { name: 'Browse products' });
  await home.waitFor({ state: 'visible' });
  assert.equal(await home.getAttribute('href'), '/');
});
