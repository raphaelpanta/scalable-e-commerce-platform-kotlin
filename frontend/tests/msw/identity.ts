import { http, HttpResponse } from 'msw';

import type { Address } from '@app/identity/identityPort';

import { API, PROBLEM } from './catalog.ts';

// Identity and the gateway's cookie-mode sign-in behind MSW for the component tests: fixed
// accounts with the states of pact-matrix.md (verified, unverified, throttled), the generic
// registration answer, single-use verification tokens and the saved addresses of the shopper.
export const SESSIONS_URL = `${API}/api/v1/identity/sessions`;
export const ACCOUNTS_URL = `${API}/api/v1/identity/accounts`;
export const VERIFY_URL = `${API}/api/v1/identity/accounts/verify-email`;
export const ME_URL = `${API}/api/v1/identity/accounts/me`;
export const ADDRESSES_URL = `${API}/api/v1/identity/accounts/me/addresses`;

export const ANA = { email: 'ana@example.com', password: 'S3cure-passphrase!' };
export const UNVERIFIED = { email: 'unverified@example.com', password: 'S3cure-passphrase!' };
export const THROTTLED = { email: 'locked@example.com', password: 'S3cure-passphrase!' };
export const GENERIC_REGISTRATION_MESSAGE =
  'If the address can be registered, a verification message has been sent.';
export const VALID_TOKEN = 'tok-valid';

export const homeAddress: Address = {
  id: '7a1c4e52-90b3-4d6f-8e21-5c3d9f0a1b22',
  label: 'Home',
  recipientName: 'Ana Silva',
  line1: 'Rua das Flores 12',
  city: 'Lisboa',
  postalCode: '1000-001',
  countryCode: 'PT',
  isDefault: true,
};

export const workAddress: Address = {
  id: '5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11',
  label: 'Work',
  recipientName: 'Ana Silva',
  line1: 'Avenida da Liberdade 100',
  city: 'Lisboa',
  postalCode: '1250-096',
  countryCode: 'PT',
  isDefault: false,
};

type IdentityServer = {
  /** True after a successful sign-in through the fake: `GET /accounts/me` then answers 200. */
  signedIn: boolean;
  addresses: Address[];
  usedTokens: Set<string>;
  requests: Array<{ method: string; url: string; body: unknown }>;
  reset(): void;
};

export const identityServer: IdentityServer = {
  signedIn: false,
  addresses: [],
  usedTokens: new Set(),
  requests: [],
  reset() {
    this.signedIn = false;
    this.addresses = [];
    this.usedTokens = new Set();
    this.requests = [];
  },
};

function problem(
  slug: string,
  status: number,
  title: string,
  detail: string,
  extra = {},
  headers = {},
) {
  return HttpResponse.json(
    { type: `https://ecommerce.example/problems/${slug}`, title, status, detail, ...extra },
    { status, headers: { 'Content-Type': PROBLEM, ...headers } },
  );
}

async function record(request: Request): Promise<Record<string, unknown>> {
  const text = request.method === 'GET' || request.method === 'DELETE' ? '' : await request.text();
  const body = text === '' ? {} : (JSON.parse(text) as Record<string, unknown>);
  identityServer.requests.push({ method: request.method, url: request.url, body });
  return body;
}

let addressSequence = 0;

export const identityHandlers = [
  http.post(SESSIONS_URL, async ({ request }) => {
    const body = await record(request);
    if (body['email'] === THROTTLED.email) {
      return problem(
        'throttled',
        429,
        'Too many requests',
        'Too many failed sign-in attempts. Try again later.',
        {},
        { 'Retry-After': '7' },
      );
    }
    if (body['email'] === UNVERIFIED.email && body['password'] === UNVERIFIED.password) {
      return problem('forbidden', 403, 'Forbidden', 'Email address is not verified.');
    }
    if (body['email'] === ANA.email && body['password'] === ANA.password) {
      identityServer.signedIn = true;
      return HttpResponse.json({ expiresAt: '2026-10-04T10:30:00Z', roles: ['shopper'] });
    }
    return problem('unauthorized', 401, 'Unauthorized', 'Invalid credentials.');
  }),
  http.post(ACCOUNTS_URL, async ({ request }) => {
    const body = await record(request);
    if (body['password'] === body['email']) {
      return problem('validation', 422, 'Validation failed', 'Password does not meet the policy.', {
        errors: [{ field: 'password', message: 'must not be equal to the email' }],
      });
    }
    return HttpResponse.json({ message: GENERIC_REGISTRATION_MESSAGE }, { status: 202 });
  }),
  http.post(VERIFY_URL, async ({ request }) => {
    const body = await record(request);
    const token = String(body['token']);
    if (token === VALID_TOKEN && !identityServer.usedTokens.has(token)) {
      identityServer.usedTokens.add(token);
      return new HttpResponse(null, { status: 204 });
    }
    return problem('validation', 422, 'Validation failed', 'The token is invalid or expired.');
  }),
  http.get(ME_URL, async ({ request }) => {
    await record(request);
    if (!identityServer.signedIn) {
      return problem('unauthorized', 401, 'Unauthorized', 'Missing credentials.');
    }
    return HttpResponse.json({
      id: '3f2b8c0e-5d41-4a39-9c1e-0a7f6b2d4e11',
      email: ANA.email,
      emailVerified: true,
      roles: ['shopper'],
      createdAt: '2026-10-02T09:15:00Z',
    });
  }),
  http.get(ADDRESSES_URL, async ({ request }) => {
    await record(request);
    return HttpResponse.json({
      items: identityServer.addresses,
      page: 0,
      size: 20,
      totalItems: identityServer.addresses.length,
    });
  }),
  http.post(ADDRESSES_URL, async ({ request }) => {
    const body = await record(request);
    if (String(body['postalCode']) === '00000') {
      return problem('validation', 422, 'Validation failed', 'The address is not valid.', {
        errors: [{ field: 'postalCode', message: 'is not a valid postal code' }],
      });
    }
    addressSequence += 1;
    const saved: Address = {
      ...(body as Omit<Address, 'id'>),
      id: `9999${String(addressSequence).padStart(4, '0')}-aaaa-4bbb-8ccc-dddddddddddd`,
    };
    identityServer.addresses.push(saved);
    return HttpResponse.json(saved, { status: 201 });
  }),
];
