import { randomUUID } from 'node:crypto';

import type { MailpitClient } from './mailpit.ts';
import type { Credentials } from './world.ts';

// Account-side fixtures of a scenario's shopper, created through the public API like the JVM
// suite (mirrors acceptance/.../support/Accounts.kt): registration, verification through the
// Mailpit token, a bearer token for API-side arrangements (an account cart, a saved address) and
// checks (the orders that exist). The browser itself only ever uses the storefront.
type JsonRecord = Record<string, unknown>;

/** The seeded simulated payment method that is always approved (feature 004). */
const APPROVING_TOKEN = 'tok_sim_approve_4242';

export class ShopperFixtures {
  readonly #baseUrl: string;
  readonly #mailpit: MailpitClient;

  constructor(baseUrl: string, mailpit: MailpitClient) {
    this.#baseUrl = baseUrl.replace(/\/$/, '');
    this.#mailpit = mailpit;
  }

  /** Registers and verifies the account (through the emailed token) and returns a bearer token. */
  async registered(credentials: Credentials): Promise<string> {
    const known = await this.#mailpit.idsTo(credentials.email);
    await this.#request('POST', '/api/v1/identity/accounts', credentials, 202);
    const token = await this.#mailpit.awaitToken(credentials.email, known);
    await this.#request('POST', '/api/v1/identity/accounts/verify-email', { token }, 204);
    return this.bearer(credentials);
  }

  /** A bearer token of the account (API mode, no cookie). */
  async bearer(credentials: Credentials): Promise<string> {
    const body = await this.#request('POST', '/api/v1/identity/sessions', credentials, 200);
    const accessToken = body['accessToken'];
    if (typeof accessToken !== 'string') throw new Error('sign-in answered no access token');
    return accessToken;
  }

  async addToAccountCart(token: string, productId: string, quantity: number): Promise<void> {
    await this.#request('POST', '/api/v1/cart/lines', { productId, quantity }, 201, token);
  }

  /** Saves a delivery address and returns its id. */
  async addAddress(token: string, city: string): Promise<string> {
    const body = await this.#request(
      'POST',
      '/api/v1/identity/accounts/me/addresses',
      {
        label: 'Home',
        recipientName: 'Ana Silva',
        line1: 'Rua das Flores 12',
        city,
        postalCode: '1000-001',
        countryCode: 'PT',
        isDefault: true,
      },
      201,
      token,
    );
    const id = body['id'];
    if (typeof id !== 'string') throw new Error('the address was saved without an id');
    return id;
  }

  /** Places an order for `quantity` of the product through the API (approved payment); returns its id. */
  async placeOrder(
    token: string,
    addressId: string,
    productId: string,
    quantity: number,
  ): Promise<string> {
    await this.addToAccountCart(token, productId, quantity);
    const cart = await this.#request('GET', '/api/v1/cart', undefined, 200, token);
    const revision = cart['revision'];
    if (typeof revision !== 'string') throw new Error('the cart has no revision');
    const order = await this.#request(
      'POST',
      '/api/v1/orders',
      {
        addressId,
        cartRevision: revision,
        paymentMethod: { type: 'card', token: APPROVING_TOKEN },
      },
      201,
      token,
      { 'Idempotency-Key': randomUUID() },
    );
    const id = order['id'];
    if (typeof id !== 'string') throw new Error('the order was placed without an id');
    return id;
  }

  /** The orders of the account, newest first. */
  async orders(token: string): Promise<JsonRecord[]> {
    const body = await this.#request('GET', '/api/v1/orders?size=50', undefined, 200, token);
    return Array.isArray(body['items']) ? (body['items'] as JsonRecord[]) : [];
  }

  async #request(
    method: 'GET' | 'POST',
    path: string,
    body: unknown,
    expectedStatus: number,
    token?: string,
    headers: Record<string, string> = {},
  ): Promise<JsonRecord> {
    const response = await fetch(`${this.#baseUrl}${path}`, {
      method,
      headers: {
        ...headers,
        ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
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
