import assert from 'node:assert/strict';

import { Given, Then, When } from '@cucumber/cucumber';

import { linksIn } from '../support/mailpit.ts';
import type { StorefrontWorld } from '../support/world.ts';

// User story 2, the account: registration, the verification link read from the local mail inbox
// (Mailpit) and sign-in, all in the browser. Fixtures of an already registered shopper go
// through the API (support/shopper.ts), like the JVM suite.

export async function signInThroughThePage(world: StorefrontWorld): Promise<void> {
  const page = world.currentPage();
  if (!new URL(page.url()).pathname.startsWith('/sign-in')) await world.goto('/sign-in');
  await page.getByLabel('Email').fill(world.shopper.email);
  await page.getByLabel('Password').fill(world.shopper.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('button', { name: 'Sign out' }).waitFor({ state: 'visible' });
  await page.waitForLoadState('networkidle');
}

When(
  'the shopper registers with a new email address and a valid password',
  async function (this: StorefrontWorld) {
    this.knownMessages = await this.mailpit.idsTo(this.shopper.email);
    await this.goto('/register');
    const page = this.currentPage();
    await page.getByLabel('Email').fill(this.shopper.email);
    await page.getByLabel('Password').fill(this.shopper.password);
    await page.getByRole('button', { name: 'Create account' }).click();
  },
);

Then('the registration is acknowledged', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('status')
    .filter({ hasText: 'If the address can be registered' })
    .waitFor({ state: 'visible' });
});

When(
  'the shopper verifies the email through the link in the message received',
  async function (this: StorefrontWorld) {
    const message = await this.mailpit.awaitMessageTo(
      this.shopper.email,
      this.knownMessages,
      (candidate) => linksIn(candidate).some((link) => link.includes('token=')),
    );
    const link = linksIn(message).find((candidate) => candidate.includes('token='));
    assert.ok(link !== undefined, 'the verification message carries no link with a token');
    // The message links to the platform's public address; the scenario's browser uses STOREFRONT_URL.
    const url = new URL(link);
    await this.goto(`${url.pathname}${url.search}`);
    await this.currentPage()
      .getByText('Your email is verified. You can sign in now.')
      .waitFor({ state: 'visible' });
    assert.equal(new URL(this.currentPage().url()).searchParams.get('token'), null);
  },
);

When('the shopper signs in', async function (this: StorefrontWorld) {
  await signInThroughThePage(this);
});

When('the shopper signs in again', async function (this: StorefrontWorld) {
  await signInThroughThePage(this);
});

Then('the shopper is asked to sign in first', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByRole('heading', { level: 1, name: 'Sign in' }).waitFor({ state: 'visible' });
  assert.equal(new URL(page.url()).pathname, '/sign-in');
});

Given('a signed-in shopper with a saved delivery address', async function (this: StorefrontWorld) {
  this.shopperToken = await this.shoppers.registered(this.shopper);
  await this.shoppers.addAddress(this.shopperToken, 'Lisboa');
  await signInThroughThePage(this);
});

Given("the shopper's session has ended", async function (this: StorefrontWorld) {
  await this.endSession();
});
