import { MatchersV3 } from '@pact-foundation/pact';

// The service pacts describe the storefront's requests as the services receive them: the gateway
// has already injected `Authorization` (from the session cookie) and `X-Cart-Token` (from the cart
// cookie), and has replaced the empty refresh body by `{refreshToken}` (pact-matrix.md, "What the
// service pacts cover"). This stand-in performs exactly those injections around the real
// src/api clients, which themselves never see a token. Fixed identifiers are those of
// pact-interactions.md (feature 004) and pact-matrix.md.
const { regex } = MatchersV3;

export const PROBLEM = 'application/problem+json';
export const UUID_PATTERN =
  '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';
export const INSTANT_PATTERN =
  '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$';
export const BEARER_PATTERN = '^Bearer [A-Za-z0-9._~+/=-]+$';
export const BEARER = 'Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI3YzFkNGYzZSJ9.signature';

export const ADA = '7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d';
export const ORDER_1 = '0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10';
export const ESPRESSO = '9a4f1e0e-6c2d-4a17-8d3b-0c7e5f2a1b01';
export const BEANS = '3d7c2b9a-1f44-4c8e-9a52-6b0e7d3c4f02';
export const PRODUCT = '0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21';
export const MISSING_ID = '00000000-0000-4000-8000-000000000000';
export const CART = '8d3b7a10-4c52-4e96-b1a7-2f5e9c0d6b34';
export const LINE_1 = 'c1a9e2f4-6b07-4d3a-8e51-0b7d4a9c2f18';
export const LINE_2 = 'e2b0f3a5-7c18-4e4b-9f62-1c8e5b0d3a29';
export const ATTEMPT_DECLINED = '8e0d1c2b-3a4f-4b5c-9d6e-7f8a9b0c1d2e';
export const KEY_1 = '6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f';
export const KEY_2 = '3c4d5e6f-7081-4b92-a3c4-d5e6f7081b92';
export const ADDRESS_OWNED = '5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11';
export const ADDRESS_2 = '7a1c4e52-90b3-4d6f-8e21-5c3d9f0a1b22';
export const CART_TOKEN = 'tok-cart-1';

export const uuid = (example: string) => regex(UUID_PATTERN, example);
export const instant = (example: string) => regex(INSTANT_PATTERN, example);
export const bearer = () => regex(BEARER_PATTERN, BEARER);

export function money(amountMinor: number) {
  return { amountMinor: MatchersV3.integer(amountMinor), currency: regex('^[A-Z]{3}$', 'BRL') };
}

/** An RFC 9457 problem body whose `type` ends in `/problems/<slug>`. */
export function problem(slug: string, status: number, title: string, detail: string) {
  return {
    type: regex(`^.*/problems/${slug}$`, `https://ecommerce.example/problems/${slug}`),
    title: MatchersV3.like(title),
    status,
    detail: MatchersV3.like(detail),
  };
}

export type GatewayInjection = {
  /** The session cookie: the gateway injects `Authorization: Bearer ...`. */
  readonly bearer?: boolean;
  /** The cart cookie: the gateway injects `X-Cart-Token`. */
  readonly cartToken?: string;
  /** A refresh in cookie mode: the gateway supplies the body `{refreshToken}`. */
  readonly refreshToken?: string;
};

/** A `fetch` that forwards the storefront's request the way the gateway does. */
export function asGateway(
  injection: GatewayInjection = {},
): (request: Request) => Promise<Response> {
  return async (request) => {
    const headers = new Headers(request.headers);
    if (injection.bearer === true) headers.set('Authorization', BEARER);
    if (injection.cartToken !== undefined) headers.set('X-Cart-Token', injection.cartToken);
    let body: string | undefined =
      request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.text();
    if (injection.refreshToken !== undefined) {
      headers.set('Content-Type', 'application/json');
      body = JSON.stringify({ refreshToken: injection.refreshToken });
    }
    return fetch(
      new Request(request.url, {
        method: request.method,
        headers,
        ...(body === undefined || body === '' ? {} : { body }),
      }),
    );
  };
}
