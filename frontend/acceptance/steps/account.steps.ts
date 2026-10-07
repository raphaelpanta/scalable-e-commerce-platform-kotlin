import assert from 'node:assert/strict';
import { randomInt } from 'node:crypto';

import { Given, Then, When } from '@cucumber/cucumber';
import type { Locator } from 'playwright';

import { signInThroughThePage } from './identity.steps.ts';
import { linksIn } from '../support/mailpit.ts';
import type { StorefrontWorld } from '../support/world.ts';

// User story 4, the account: addresses, notification preferences (the text message code is read
// from the local mail inbox, where the simulated SMS channel mirrors it), sign-out, the password
// reset link and the account deletion, all in the browser. Registration and verification of the
// scenario's shopper go through the API, like the JVM suite.

async function openAddresses(world: StorefrontWorld): Promise<void> {
  if (new URL(world.currentPage().url()).pathname !== '/account/addresses') {
    await world.goto('/account/addresses');
  }
  await world
    .currentPage()
    .getByRole('heading', { level: 1, name: 'Your addresses' })
    .waitFor({ state: 'visible' });
}

async function openPreferences(world: StorefrontWorld): Promise<void> {
  await world.goto('/account/notifications');
  await world
    .currentPage()
    .getByRole('heading', { level: 1, name: 'Notification preferences' })
    .waitFor({ state: 'visible' });
}

async function fillAddress(form: Locator, city: string): Promise<void> {
  await form.getByLabel('Recipient name').fill('Ana Silva');
  await form.getByLabel('Address line 1').fill('Rua das Flores 12');
  await form.getByLabel('City').fill(city);
  await form.getByLabel('Postal code').fill('1000-001');
  await form.getByLabel('Country code').fill('PT');
  await form.getByLabel('Label (optional)').fill(city);
}

function smsAddress(phone: string): string {
  return `sms-${phone.replace('+', '')}@sms.ecommerce.invalid`;
}

Given('a signed-in shopper', async function (this: StorefrontWorld) {
  this.shopperToken = await this.shoppers.registered(this.shopper);
  await signInThroughThePage(this);
});

Given('a registered shopper', async function (this: StorefrontWorld) {
  this.shopperToken = await this.shoppers.registered(this.shopper);
});

When(
  'the shopper adds a delivery address in {string}',
  async function (this: StorefrontWorld, city: string) {
    await openAddresses(this);
    const page = this.currentPage();
    await page.getByRole('button', { name: 'Add an address' }).click();
    const form = page.getByRole('form', { name: 'New delivery address' });
    await fillAddress(form, city);
    await form.getByRole('button', { name: 'Save address' }).click();
    await page
      .getByRole('status')
      .filter({ hasText: 'Address saved.' })
      .waitFor({ state: 'visible' });
    await page.getByRole('button', { name: `Edit address ${city}` }).waitFor({ state: 'visible' });
  },
);

When(
  'the shopper changes the {string} address to {string}',
  async function (this: StorefrontWorld, from: string, to: string) {
    await openAddresses(this);
    const page = this.currentPage();
    await page.getByRole('button', { name: `Edit address ${from}` }).click();
    const form = page.getByRole('form', { name: 'Edit delivery address' });
    await form.getByLabel('City').fill(to);
    await form.getByLabel('Label (optional)').fill(to);
    await form.getByRole('button', { name: 'Save address' }).click();
    await page.getByRole('button', { name: `Edit address ${to}` }).waitFor({ state: 'visible' });
  },
);

When(
  'the shopper removes the {string} address',
  async function (this: StorefrontWorld, city: string) {
    await openAddresses(this);
    const page = this.currentPage();
    await page.getByRole('button', { name: `Delete address ${city}` }).click();
    await page
      .getByRole('dialog', { name: 'Delete this address?' })
      .getByRole('button', { name: 'Delete address' })
      .click();
    await page
      .getByRole('status')
      .filter({ hasText: 'Address removed.' })
      .waitFor({ state: 'visible' });
  },
);

When('the shopper signs out and signs in again', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.getByRole('link', { name: 'Sign in' }).first().waitFor({ state: 'visible' });
  await signInThroughThePage(this);
});

Then(
  "the shopper's delivery addresses are exactly {string}",
  async function (this: StorefrontWorld, city: string) {
    await this.goto('/account/addresses');
    const rows = this.currentPage()
      .getByRole('list', { name: 'Saved addresses' })
      .getByRole('listitem');
    await rows.first().waitFor({ state: 'visible' });
    const texts = await rows.allTextContents();
    assert.equal(texts.length, 1, `addresses: ${texts.join(' | ')}`);
    assert.match(texts[0] ?? '', new RegExp(city));
  },
);

When('the shopper opens the notification preferences', async function (this: StorefrontWorld) {
  await openPreferences(this);
});

Then('email notifications are on', async function (this: StorefrontWorld) {
  assert.equal(await this.currentPage().getByRole('checkbox', { name: 'Email' }).isChecked(), true);
});

Then('text messages cannot be chosen yet', async function (this: StorefrontWorld) {
  const sms = this.currentPage().getByRole('checkbox', { name: 'SMS' });
  assert.equal(await sms.isDisabled(), true);
  assert.equal(await sms.isChecked(), false);
});

When(
  'the shopper verifies a new phone number with the code received by text message',
  async function (this: StorefrontWorld) {
    const phone = `+3519${String(randomInt(10_000_000, 99_999_999))}`;
    this.phoneNumber = phone;
    const known = await this.mailpit.idsTo(smsAddress(phone));
    const page = this.currentPage();
    await page.getByLabel('Phone number').fill(phone);
    await page.getByRole('button', { name: 'Send code' }).click();
    await page.getByText(`We sent a code to ${phone}.`).waitFor({ state: 'visible' });
    const message = await this.mailpit.awaitMessageTo(smsAddress(phone), known, (candidate) =>
      /\b\d{6}\b/.test(candidate.text),
    );
    const code = /\b(\d{6})\b/.exec(message.text)?.[1];
    assert.ok(code !== undefined, 'the text message carries no six digit code');
    await page.getByLabel('Verification code').fill(code);
    await page.getByRole('button', { name: 'Confirm number' }).click();
    await page.getByText(`Your phone number ${phone} is verified.`).waitFor({ state: 'visible' });
  },
);

When('the shopper turns on text messages and saves', async function (this: StorefrontWorld) {
  const page = this.currentPage();
  const sms = page.getByRole('checkbox', { name: 'SMS' });
  await sms.waitFor({ state: 'visible' });
  await page.waitForFunction(
    () => document.querySelector<HTMLInputElement>('input[type="checkbox"]:disabled') === null,
  );
  await sms.check();
  await page.getByRole('button', { name: 'Save preferences' }).click();
});

Then('the preferences are saved', async function (this: StorefrontWorld) {
  await this.currentPage()
    .getByRole('status')
    .filter({ hasText: 'Your preferences are saved.' })
    .waitFor({ state: 'visible' });
});

Then(
  'email and text messages are both on after reloading the page',
  async function (this: StorefrontWorld) {
    await this.currentPage().reload({ waitUntil: 'networkidle' });
    const page = this.currentPage();
    await page.getByRole('checkbox', { name: 'Email' }).waitFor({ state: 'visible' });
    assert.equal(await page.getByRole('checkbox', { name: 'Email' }).isChecked(), true);
    assert.equal(await page.getByRole('checkbox', { name: 'SMS' }).isChecked(), true);
  },
);

When('the shopper asks for a password reset', async function (this: StorefrontWorld) {
  this.knownMessages = await this.mailpit.idsTo(this.shopper.email);
  await this.goto('/forgot-password');
  const page = this.currentPage();
  await page.getByLabel('Email').fill(this.shopper.email);
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await page.getByRole('status').filter({ hasText: 'reset message' }).waitFor({ state: 'visible' });
});

Then(
  'the confirmation is the same for an email nobody registered',
  async function (this: StorefrontWorld) {
    const page = this.currentPage();
    const known = (await page.getByRole('status').first().textContent()) ?? '';
    await page.getByLabel('Email').fill(`nobody-${this.shopper.email}`);
    await page.getByRole('button', { name: 'Send reset link' }).click();
    await page.waitForLoadState('networkidle');
    await page
      .getByRole('status')
      .filter({ hasText: 'reset message' })
      .waitFor({ state: 'visible' });
    assert.equal((await page.getByRole('status').first().textContent()) ?? '', known);
  },
);

Then('a reset message arrives for the shopper', async function (this: StorefrontWorld) {
  const message = await this.mailpit.awaitMessageTo(
    this.shopper.email,
    this.knownMessages,
    (candidate) => linksIn(candidate).some((link) => link.includes('/reset-password?token=')),
  );
  const link = linksIn(message).find((candidate) => candidate.includes('/reset-password?token='));
  assert.ok(link !== undefined, 'the reset message carries no reset link');
  this.resetLink = link;
});

When(
  'the shopper chooses a new password with the link in the message',
  async function (this: StorefrontWorld) {
    assert.ok(this.resetLink !== undefined, 'no reset link was received');
    // The message links to the platform's public address; the scenario's browser uses STOREFRONT_URL.
    const url = new URL(this.resetLink);
    await this.goto(`${url.pathname}${url.search}`);
    const page = this.currentPage();
    await page.getByLabel('New password').waitFor({ state: 'visible' });
    assert.equal(new URL(page.url()).searchParams.get('token'), null);
    this.newPassword = `${this.shopper.password}-new`;
    await page.getByLabel('New password').fill(this.newPassword);
    await page.getByRole('button', { name: 'Set new password' }).click();
    await page
      .getByRole('status')
      .filter({ hasText: 'Your password was changed.' })
      .waitFor({ state: 'visible' });
  },
);

async function signInWith(world: StorefrontWorld, password: string): Promise<void> {
  await world.goto('/sign-in');
  const page = world.currentPage();
  await page.getByLabel('Email').fill(world.shopper.email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

Then('signing in with the old password is refused', async function (this: StorefrontWorld) {
  await signInWith(this, this.shopper.password);
  await this.currentPage()
    .getByRole('alert')
    .filter({ hasText: 'The email or password is incorrect.' })
    .waitFor({ state: 'visible' });
});

Then('signing in with the new password succeeds', async function (this: StorefrontWorld) {
  assert.ok(this.newPassword !== undefined, 'no new password was chosen');
  await signInWith(this, this.newPassword);
  await this.currentPage().getByRole('button', { name: 'Sign out' }).waitFor({ state: 'visible' });
});

Then('the same reset link cannot be used a second time', async function (this: StorefrontWorld) {
  assert.ok(this.resetLink !== undefined, 'no reset link was received');
  const url = new URL(this.resetLink);
  await this.goto(`${url.pathname}${url.search}`);
  const page = this.currentPage();
  await page.getByLabel('New password').fill(`${this.shopper.password}-again`);
  await page.getByRole('button', { name: 'Set new password' }).click();
  await page
    .getByRole('alert')
    .filter({ hasText: 'This reset link is invalid or has expired.' })
    .waitFor({ state: 'visible' });
});

When(
  'the shopper starts to delete the account with a wrong password',
  async function (this: StorefrontWorld) {
    await this.goto('/account');
    const page = this.currentPage();
    await page.getByRole('button', { name: 'Delete my account' }).click();
    const dialog = page.getByRole('dialog', { name: 'Delete your account?' });
    await dialog.getByLabel('Password').fill('a-wrong-passphrase!');
    await dialog.getByRole('button', { name: 'Delete my account' }).click();
  },
);

Then(
  'the account is not deleted and the password is reported as incorrect',
  async function (this: StorefrontWorld) {
    const dialog = this.currentPage().getByRole('dialog', { name: 'Delete your account?' });
    await dialog.getByText('The password is incorrect.').waitFor({ state: 'visible' });
    assert.ok(this.shopperToken !== undefined);
    const token = await this.shoppers.bearer(this.shopper);
    assert.ok(token.length > 0, 'the account no longer signs in');
  },
);

When(
  'the shopper deletes the account confirming with their password',
  async function (this: StorefrontWorld) {
    const dialog = this.currentPage().getByRole('dialog', { name: 'Delete your account?' });
    await dialog.getByLabel('Password').fill(this.shopper.password);
    await dialog.getByRole('button', { name: 'Delete my account' }).click();
  },
);

Then(
  'the shopper is told the account was deleted and is signed out',
  async function (this: StorefrontWorld) {
    const page = this.currentPage();
    await page
      .getByRole('heading', { level: 1, name: 'Your account was deleted' })
      .waitFor({ state: 'visible' });
    await page.getByRole('link', { name: 'Sign in' }).first().waitFor({ state: 'visible' });
    assert.equal(await page.getByRole('button', { name: 'Sign out' }).count(), 0);
  },
);

Then('signing in with the deleted account is refused', async function (this: StorefrontWorld) {
  await signInWith(this, this.shopper.password);
  await this.currentPage()
    .getByRole('alert')
    .filter({ hasText: 'The email or password is incorrect.' })
    .waitFor({ state: 'visible' });
});
