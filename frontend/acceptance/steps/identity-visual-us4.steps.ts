import assert from 'node:assert/strict';

import { Then } from '@cucumber/cucumber';

import type { StorefrontWorld } from '../support/world.ts';

// Vibestore visual identity, user story 4: the operator console under the same brand. Steps bind
// to roles and accessible names only, never to styling.

Then(
  'the console shows the Vibestore name beside {string}',
  async function (this: StorefrontWorld, section: string) {
    const page = this.currentPage();
    await page.getByText('Vibestore', { exact: true }).first().waitFor({ state: 'visible' });
    assert.ok((await page.getByText(section, { exact: true }).count()) >= 1);
    await page.getByRole('navigation', { name: 'Console' }).waitFor({ state: 'visible' });
  },
);

Then('the console offers the orders list', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByRole('heading', { level: 1, name: 'Orders' }).waitFor({ state: 'visible' });
  await page
    .getByRole('navigation', { name: 'Console' })
    .getByRole('link', { name: 'Orders' })
    .waitFor({ state: 'visible' });
});
