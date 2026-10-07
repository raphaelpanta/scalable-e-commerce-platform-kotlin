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

export class StorefrontWorld extends World {
  readonly baseUrl: string;
  readonly mailpit: MailpitClient;
  readonly operator: Credentials;
  readonly shopper: Credentials;
  readonly viewport: ViewportMode;
  /** Catalogue fixtures created through the API as the operator, by alias of the feature file. */
  readonly catalogue: CatalogueFixtures;
  readonly categories = new Map<string, CategoryRef>();
  readonly products = new Map<string, ProductRef>();
  /** The search term of the scenario, when one was coined. */
  searchTerm: string | undefined;
  /** URLs already audited for accessibility in this scenario. */
  readonly audited = new Set<string>();
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
    this.context = await browser.newContext({
      viewport: VIEWPORTS[this.viewport],
      locale: 'en-US',
      ...(this.viewport === 'mobile' ? { isMobile: true, hasTouch: true } : {}),
    });
    this.page = await this.context.newPage();
    return this.page;
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
