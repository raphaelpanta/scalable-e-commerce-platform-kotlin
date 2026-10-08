import assert from 'node:assert/strict';

import { Given, Then } from '@cucumber/cucumber';

import { expectAccessible } from '../support/a11y.ts';
import type { StorefrontWorld } from '../support/world.ts';

// Vibestore visual identity, user story 3: the identity stays usable in a dark appearance and on a
// narrow phone screen. The appearance and the screen size are shopper preferences; the checks are
// outcomes (the audit passes, nothing scrolls sideways), never styling.

const NARROW_PHONE = { width: 360, height: 780 };

Given('the shopper prefers a dark appearance', async function (this: StorefrontWorld) {
  await this.currentPage().emulateMedia({ colorScheme: 'dark' });
});

Given('the shopper uses a narrow phone screen', async function (this: StorefrontWorld) {
  await this.currentPage().setViewportSize(NARROW_PHONE);
});

Then('the page passes the accessibility audit', async function (this: StorefrontWorld) {
  await expectAccessible(this.currentPage());
});

Then('the page does not scroll sideways', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByRole('main').waitFor({ state: 'visible' });
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  assert.ok(overflow <= 0, `${page.url()} scrolls sideways by ${String(overflow)} px`);
});
