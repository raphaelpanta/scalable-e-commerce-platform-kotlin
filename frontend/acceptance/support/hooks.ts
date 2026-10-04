import { After, AfterAll, AfterStep, Before, BeforeAll, Status } from '@cucumber/cucumber';
import { type Browser, chromium } from 'playwright';

import { expectAccessible } from './a11y.ts';
import type { StorefrontWorld } from './world.ts';

// Browser lifecycle: one Chromium per run, one context and page per scenario, an axe audit of
// every page the scenario visits (critical/serious violations fail it, SC-006).
let browser: Browser | undefined;

BeforeAll(async function () {
  browser = await chromium.launch({ headless: process.env['HEADED'] !== '1' });
});

Before(async function (this: StorefrontWorld) {
  if (browser === undefined) throw new Error('browser was not launched');
  await this.open(browser);
});

AfterStep(async function (this: StorefrontWorld, { result }) {
  if (result.status !== Status.PASSED || this.page === undefined) return;
  const url = this.page.url();
  if (url === 'about:blank' || this.audited.has(url)) return;
  this.audited.add(url);
  await expectAccessible(this.page);
});

After(async function (this: StorefrontWorld, { result, pickle }) {
  if (result?.status !== Status.PASSED && this.page !== undefined) {
    const screenshot = await this.page.screenshot({ fullPage: true });
    this.attach(screenshot, 'image/png');
    this.attach(`${pickle.name}: ${this.page.url()}`, 'text/plain');
  }
  await this.close();
});

AfterAll(async function () {
  await browser?.close();
  browser = undefined;
});
