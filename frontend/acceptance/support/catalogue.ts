import { randomUUID } from 'node:crypto';

import type { Credentials } from './world.ts';

// Catalogue fixtures for the browsing scenarios, created through the public API as the seeded
// operator (mirrors acceptance/.../support/Catalogue.kt): unique names per scenario so reruns
// never collide, a primary image on every product, withdrawal for the "never shown" cases.
export type ProductRef = {
  readonly alias: string;
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly priceMinor: number;
  readonly categoryId: string;
  /** Units in stock when the product was created. */
  readonly stock: number;
};

export type CategoryRef = { readonly alias: string; readonly id: string; readonly name: string };

export const DEFAULT_PRICE_MINOR = 1000;
export const DEFAULT_STOCK = 5;

/** Amounts as the storefront renders them for the acceptance browser (en-US, BRL). */
export function formatPrice(priceMinor: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'BRL' }).format(
    priceMinor / 100,
  );
}

/** `25.00` of a feature file as minor units. */
export function minorUnits(major: number): number {
  return Math.round(major * 100);
}

export function suffix(): string {
  return randomUUID().slice(0, 8);
}

/** A unique lower-case word no seeded product can contain. */
export function newSearchTerm(): string {
  return `zq${randomUUID().replace(/-/g, '').replace(/\d/g, '').slice(0, 10)}`;
}

type JsonRecord = Record<string, unknown>;

export class CatalogueFixtures {
  readonly #baseUrl: string;
  readonly #operator: Credentials;
  // One operator sign-in per process: the gateway's auth tier allows 10 sign-ins per minute per address.
  static #token: string | undefined;
  #scenarioCategory: string | undefined;

  constructor(baseUrl: string, operator: Credentials) {
    this.#baseUrl = baseUrl.replace(/\/$/, '');
    this.#operator = operator;
  }

  /** The catalogue answers anonymously: the stack is up and seeded. */
  async assertSeeded(): Promise<void> {
    const response = await fetch(`${this.#baseUrl}/api/v1/catalog/products?size=1`);
    if (!response.ok) throw new Error(`catalogue not reachable: ${response.status}`);
  }

  async createCategory(alias: string): Promise<CategoryRef> {
    const name = `${alias} ${suffix()}`;
    const body = await this.#post('/api/v1/catalog/categories', { name }, 201);
    return { alias, id: requireString(body, 'id'), name };
  }

  async createProduct(
    alias: string,
    options: {
      readonly name?: string;
      readonly description?: string;
      readonly stock?: number;
      readonly priceMinor?: number;
      readonly categoryId?: string;
    } = {},
  ): Promise<ProductRef> {
    const name = options.name ?? `${alias} ${suffix()}`;
    const description = options.description ?? `The ${alias.toLowerCase()} every garden needs.`;
    const priceMinor = options.priceMinor ?? DEFAULT_PRICE_MINOR;
    const categoryId = options.categoryId ?? (await this.#scenarioCategoryId());
    const stock = options.stock ?? DEFAULT_STOCK;
    const body = await this.#post(
      '/api/v1/catalog/products',
      {
        name,
        description,
        price: { amountMinor: priceMinor, currency: 'BRL' },
        categoryId,
        initialStock: stock,
      },
      201,
    );
    const id = requireString(body, 'id');
    await this.#post(
      `/api/v1/catalog/products/${id}/images`,
      { url: `https://cdn.example.test/${id}.jpg`, altText: alias, primary: true },
      201,
    );
    return { alias, id, name, description, priceMinor, categoryId, stock };
  }

  async withdraw(productId: string): Promise<void> {
    await this.#post(`/api/v1/catalog/products/${productId}/withdrawal`, undefined, 200);
  }

  /** An operator changes the price (`updateProduct`); the cart then reports `priceChanged`. */
  async updatePrice(product: ProductRef, priceMinor: number): Promise<ProductRef> {
    await this.#send(
      'PUT',
      `/api/v1/catalog/products/${product.id}`,
      {
        name: product.name,
        description: product.description,
        price: { amountMinor: priceMinor, currency: 'BRL' },
        categoryId: product.categoryId,
      },
      200,
    );
    return { ...product, priceMinor };
  }

  /** An operator removes every unit (`adjustStock` with a negative delta). */
  async removeStock(product: ProductRef): Promise<void> {
    await this.#post(
      `/api/v1/catalog/products/${product.id}/stock-adjustments`,
      { delta: -product.stock, reason: 'acceptance: product went out of stock' },
      201,
    );
  }

  async #scenarioCategoryId(): Promise<string> {
    this.#scenarioCategory ??= (await this.createCategory('Acceptance')).id;
    return this.#scenarioCategory;
  }

  async #bearer(): Promise<string> {
    if (CatalogueFixtures.#token !== undefined) return CatalogueFixtures.#token;
    const response = await fetch(`${this.#baseUrl}/api/v1/identity/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: this.#operator.email, password: this.#operator.password }),
    });
    if (response.status !== 200) {
      throw new Error(`operator sign-in failed with status ${response.status}`);
    }
    const body = (await response.json()) as JsonRecord;
    CatalogueFixtures.#token = requireString(body, 'accessToken');
    return CatalogueFixtures.#token;
  }

  async #post(path: string, body: unknown, expectedStatus: number): Promise<JsonRecord> {
    return this.#send('POST', path, body, expectedStatus);
  }

  async #send(
    method: 'POST' | 'PUT',
    path: string,
    body: unknown,
    expectedStatus: number,
  ): Promise<JsonRecord> {
    const token = await this.#bearer();
    const response = await fetch(`${this.#baseUrl}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (response.status !== expectedStatus) {
      throw new Error(`${method} ${path} answered ${response.status}, expected ${expectedStatus}`);
    }
    const text = await response.text();
    return text === '' ? {} : (JSON.parse(text) as JsonRecord);
  }
}

function requireString(record: JsonRecord, key: string): string {
  const value = record[key];
  if (typeof value !== 'string' || value === '') throw new Error(`response has no "${key}"`);
  return value;
}
