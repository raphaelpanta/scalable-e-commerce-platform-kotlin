import type { Page } from '@playwright/test';

import { CONFIRMED_ORDER_ID, type Scenario } from './harness/scenarios.ts';
import { gardenTools, rake } from '../tests/msw/catalog.ts';

// The pages of the visual matrix (contracts/visual-baselines.md) and the console pages the
// accessibility audit adds, each with the harness scenario it renders under.
export type VisualPage = {
  readonly name: string;
  readonly route: string;
  readonly scenario: Scenario;
};

export const PAGES: readonly VisualPage[] = [
  { name: 'home', route: '/', scenario: 'anonymous' },
  { name: 'category', route: `/categories/${gardenTools.id}`, scenario: 'anonymous' },
  { name: 'product', route: `/products/${rake.id}`, scenario: 'anonymous' },
  { name: 'cart', route: '/cart', scenario: 'shopper' },
  { name: 'checkout', route: '/checkout', scenario: 'shopper' },
  {
    name: 'confirmation',
    route: `/orders/${CONFIRMED_ORDER_ID}/confirmation`,
    scenario: 'shopper',
  },
  { name: 'orders', route: '/orders', scenario: 'shopper' },
  { name: 'sign-in', route: '/sign-in', scenario: 'anonymous' },
];

export const CONSOLE_PAGES: readonly VisualPage[] = [
  { name: 'console-orders', route: '/console/orders', scenario: 'operator' },
  { name: 'console-stock', route: '/console/stock', scenario: 'operator' },
];

export function pageNamed(name: string): VisualPage {
  const found = [...PAGES, ...CONSOLE_PAGES].find((candidate) => candidate.name === name);
  if (found === undefined) throw new Error(`unknown visual page ${name}`);
  return found;
}

/** The harness URL of a page: its route plus the `?scenario=` the harness seeds from. */
export function harnessUrl(visual: VisualPage, scenario: Scenario = visual.scenario): string {
  const url = new URL(visual.route, 'http://localhost');
  url.searchParams.set('scenario', scenario);
  return `${url.pathname}${url.search}`;
}

/** Opens a page and waits until it settled: data loaded, heading shown, fonts ready. */
export async function openPage(
  page: Page,
  visual: VisualPage,
  scenario: Scenario = visual.scenario,
): Promise<void> {
  await page.goto(harnessUrl(visual, scenario));
  await settle(page);
}

/**
 * Static files reach the page through the MSW service worker, which `networkidle` does not count,
 * so images and fonts are awaited explicitly.
 */
export async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  await page.locator('main h1').first().waitFor({ state: 'visible' });
  await page.waitForFunction(
    () =>
      document.querySelector('[aria-busy="true"]') === null &&
      [...document.images].every((image) => image.complete),
  );
  await page.evaluate(async () => {
    await document.fonts.ready;
  });
}
