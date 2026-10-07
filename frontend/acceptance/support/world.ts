import { randomUUID } from 'node:crypto';

import {
  type IWorldOptions,
  setDefaultTimeout,
  setWorldConstructor,
  World,
} from '@cucumber/cucumber';
import type { Browser, BrowserContext, Page } from 'playwright';

import { type CategoryRef, CatalogueFixtures, type ProductRef } from './catalogue.ts';
import { MailpitClient } from './mailpit.ts';
import { ShopperFixtures } from './shopper.ts';

// One Playwright page per scenario against STOREFRONT_URL (the gateway origin), the Mailpit
// inbox for verification and reset links, the seeded operator of feature 004 and a fresh shopper
// address per scenario. STOREFRONT_VIEWPORT=mobile runs every scenario at the 360 px baseline.
export type ViewportMode = 'desktop' | 'mobile';

export const VIEWPORTS: Readonly<Record<ViewportMode, { width: number; height: number }>> = {
  desktop: { width: 1280, height: 800 },
  mobile: { width: 360, height: 780 },
};

export function viewportMode(env: NodeJS.ProcessEnv = process.env): ViewportMode {
  return env['STOREFRONT_VIEWPORT'] === 'mobile' ? 'mobile' : 'desktop';
}

export type Credentials = { readonly email: string; readonly password: string };

export function environment(env: NodeJS.ProcessEnv = process.env): {
  baseUrl: string;
  mailpitUrl: string;
  operator: Credentials;
  viewport: ViewportMode;
} {
  const baseUrl = env['STOREFRONT_URL'];
  if (baseUrl === undefined || baseUrl === '') {
    throw new Error('STOREFRONT_URL must point at the gateway (for example http://localhost:8080)');
  }
  return {
    baseUrl: baseUrl.replace(/\/$/, ''),
    mailpitUrl: env['MAILPIT_URL'] ?? 'http://localhost:8025',
    operator: {
      email: env['OPERATOR_EMAIL'] ?? 'operator@ecommerce.example',
      password: env['OPERATOR_PASSWORD'] ?? 'Operator-Passw0rd!2026',
    },
    viewport: viewportMode(env),
  };
}

export function randomShopper(): Credentials {
  const suffix = randomUUID().slice(0, 8);
  return { email: `shopper-${suffix}@storefront.test`, password: `Shopper-${suffix}-Passw0rd!` };
}

/** A 1x1 PNG served for the fixture image host, so product images load instead of falling back to the placeholder. */
const FIXTURE_IMAGE_HOST = 'https://cdn.example.test/**';
const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

async function serveFixtureImages(page: Page): Promise<void> {
  await page.route(FIXTURE_IMAGE_HOST, (route) =>
    route.fulfill({ status: 200, contentType: 'image/png', body: ONE_PIXEL_PNG }),
  );
}

export class StorefrontWorld extends World {
  readonly baseUrl: string;
  readonly mailpit: MailpitClient;
  readonly operator: Credentials;
  readonly shopper: Credentials;
  readonly viewport: ViewportMode;
  /** Catalogue fixtures created through the API as the operator, by alias of the feature file. */
  readonly catalogue: CatalogueFixtures;
  /** Account fixtures of the scenario's shopper, created through the API (register, verify, cart). */
  readonly shoppers: ShopperFixtures;
  readonly categories = new Map<string, CategoryRef>();
  readonly products = new Map<string, ProductRef>();
  /** The search term of the scenario, when one was coined. */
  searchTerm: string | undefined;
  /** A bearer token of the scenario's shopper for API-side fixtures and checks (never the page's). */
  shopperToken: string | undefined;
  /** Mailpit message ids seen before the step that awaits a new message. */
  knownMessages = new Set<string>();
  /** URLs already audited for accessibility in this scenario. */
  readonly audited = new Set<string>();
  browser: Browser | undefined;
  context: BrowserContext | undefined;
  page: Page | undefined;

  constructor(options: IWorldOptions) {
    super(options);
    const env = environment();
    this.baseUrl = env.baseUrl;
    this.mailpit = new MailpitClient(env.mailpitUrl);
    this.operator = env.operator;
    this.shopper = randomShopper();
    this.viewport = env.viewport;
    this.catalogue = new CatalogueFixtures(env.baseUrl, env.operator);
    this.shoppers = new ShopperFixtures(env.baseUrl, this.mailpit);
  }

  category(alias: string): CategoryRef {
    const found = this.categories.get(alias);
    if (found === undefined) throw new Error(`no category "${alias}" in this scenario`);
    return found;
  }

  product(alias: string): ProductRef {
    const found = this.products.get(alias);
    if (found === undefined) throw new Error(`no product "${alias}" in this scenario`);
    return found;
  }

  async open(browser: Browser): Promise<Page> {
    this.browser = browser;
    this.context = await browser.newContext(this.#contextOptions());
    this.page = await this.context.newPage();
    await serveFixtureImages(this.page);
    return this.page;
  }

  /**
   * Closes the browser context and opens a new one with the same cookies (a browser restart on
   * the same device, US2 scenario 1): the cart cookie survives, the session cookie, without a
   * `Max-Age`, is dropped like a browser would.
   */
  async reopen(): Promise<Page> {
    if (this.browser === undefined || this.context === undefined) {
      throw new Error('no browser is open for this scenario');
    }
    const state = await this.context.storageState();
    const kept = state.cookies.filter((cookie) => cookie.expires !== -1);
    await this.context.close();
    this.context = await this.browser.newContext({
      ...this.#contextOptions(),
      storageState: { cookies: kept, origins: [] },
    });
    this.page = await this.context.newPage();
    await serveFixtureImages(this.page);
    return this.page;
  }

  /** Drops the session cookie (idle expiry, revocation): the next protected call answers 401. */
  async endSession(): Promise<void> {
    await this.context?.clearCookies({ name: /^(__Host-)?session$/ });
  }

  #contextOptions(): Parameters<Browser['newContext']>[0] {
    return {
      viewport: VIEWPORTS[this.viewport],
      locale: 'en-US',
      ...(this.viewport === 'mobile' ? { isMobile: true, hasTouch: true } : {}),
    };
  }

  /** The current page; every step runs after the Before hook opened it. */
  currentPage(): Page {
    if (this.page === undefined) throw new Error('no page is open for this scenario');
    return this.page;
  }

  async goto(path: string): Promise<void> {
    await this.currentPage().goto(`${this.baseUrl}${path}`, { waitUntil: 'networkidle' });
  }

  async close(): Promise<void> {
    await this.context?.close();
    this.context = undefined;
    this.page = undefined;
  }
}

setDefaultTimeout(60_000);
setWorldConstructor(StorefrontWorld);
