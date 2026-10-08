import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import type { Credentials, StorefrontWorld } from '../support/world.ts';

// Shared steps in the ubiquitous language of acceptance/*.feature (feature 004). Story-specific
// steps live next to their feature (browsing, cart, identity, checkout, orders, account, console).

Given('I open the storefront', async function (this: StorefrontWorld) {
  await this.goto('/');
});

Given('I open the page {string}', async function (this: StorefrontWorld, path: string) {
  await this.goto(path);
});

When('I reload the page', async function (this: StorefrontWorld) {
  await this.currentPage().reload({ waitUntil: 'networkidle' });
});

Then('I see {string}', async function (this: StorefrontWorld, text: string) {
  await this.currentPage().getByText(text, { exact: false }).first().waitFor({ state: 'visible' });
});

Then('I do not see {string}', async function (this: StorefrontWorld, text: string) {
  const matches = await this.currentPage().getByText(text, { exact: false }).count();
  assert.equal(matches, 0, `expected no element with text "${text}"`);
});

Then('I see the heading {string}', async function (this: StorefrontWorld, heading: string) {
  await this.currentPage()
    .getByRole('heading', { level: 1, name: heading })
    .waitFor({ state: 'visible' });
});

Then('the address is {string}', function (this: StorefrontWorld, path: string) {
  const url = new URL(this.currentPage().url());
  assert.equal(`${url.pathname}${url.search}`, path);
});

Then('I see the not-found page', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('heading', { level: 1, name: 'Page not found' })
    .waitFor({ state: 'visible' });
});

When('I follow the link {string}', async function (this: StorefrontWorld, name: string) {
  await this.currentPage().getByRole('link', { name }).first().click();
  await this.currentPage().waitForLoadState('networkidle');
});

When('I press the button {string}', async function (this: StorefrontWorld, name: string) {
  await this.currentPage().getByRole('button', { name }).first().click();
});

When(
  'I fill {string} with {string}',
  async function (this: StorefrontWorld, label: string, value: string) {
    await this.currentPage().getByLabel(label).fill(value);
  },
);

async function signInWith(world: StorefrontWorld, credentials: Credentials): Promise<void> {
  await world.goto('/sign-in');
  const page = world.currentPage();
  await page.getByLabel('Email').fill(credentials.email);
  await page.getByLabel('Password').fill(credentials.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForLoadState('networkidle');
}

When('I sign in as the operator', async function (this: StorefrontWorld) {
  await signInWith(this, this.operator);
});

/** The shopper of this scenario (registered and verified by an earlier step). */
When('I sign in as the shopper', async function (this: StorefrontWorld) {
  await signInWith(this, this.shopper);
});

When('I sign out', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByRole('button', { name: 'Sign out' }).click();
  // Signing out is complete once the banner offers to sign in again (the session request has answered).
  await page.getByRole('link', { name: 'Sign in' }).waitFor({ state: 'visible' });
});

// Keyboard-only navigation helpers (SC-006).

When('I press {string}', async function (this: StorefrontWorld, key: string) {
  await this.currentPage().keyboard.press(key);
});

When('I press Tab {int} times', async function (this: StorefrontWorld, times: number) {
  for (let index = 0; index < times; index += 1) {
    await this.currentPage().keyboard.press('Tab');
  }
});

When('I tab to {string}', async function (this: StorefrontWorld, name: string) {
  const page = this.currentPage();
  for (let index = 0; index < 60; index += 1) {
    const focused = await page.evaluate(() => {
      const element = document.activeElement;
      if (element === null) return '';
      const label = element.getAttribute('aria-label');
      return (label ?? element.textContent).trim();
    });
    if (focused === name || focused.startsWith(name)) return;
    await page.keyboard.press('Tab');
  }
  assert.fail(`no focusable element named "${name}" reached by Tab`);
});

When('I activate the focused element', async function (this: StorefrontWorld) {
  await this.currentPage().keyboard.press('Enter');
  await this.currentPage().waitForLoadState('networkidle');
});

Then('the focused element is {string}', async function (this: StorefrontWorld, name: string) {
  const focused = await this.currentPage().evaluate(() => {
    const element = document.activeElement;
    if (element === null) return '';
    const label = element.getAttribute('aria-label');
    return (label ?? element.textContent).trim();
  });
  assert.ok(
    focused === name || focused.startsWith(name),
    `expected focus on "${name}" but it is on "${focused}"`,
  );
});

Then('the focused element is visible', async function (this: StorefrontWorld) {
  const visible = await this.currentPage().evaluate(() => {
    const element = document.activeElement;
    if (element === null || element === document.body) return false;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return rect.width > 0 && rect.height > 0 && style.outlineStyle !== 'none';
  });
  assert.ok(visible, 'the focused element must have a visible focus indicator');
});
