import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createApiClient } from '@api/client';
import type { paths as CartPaths } from '@api/generated/cart';
import type { paths as GatewayPaths } from '@api/generated/gateway-browser-session';
import type { paths as TelemetryPaths } from '@api/generated/telemetry';
import { ProblemError, ThrottledError, UnauthorizedError } from '@api/problem';
import { createSessionPort } from '@api/session';
import { Email } from '@domain/email';
import { Password } from '@domain/password';

import { pactFor } from './pact.config.ts';

// Storefront → gateway consumer pact: interactions G1–G19 of contracts/pact-matrix.md ("Storefront
// to gateway"), provider states verbatim from the gateway's StorefrontProviderStates. Cookie names
// are the plain HTTP ones (`session`, `cart`) because the provider verification runs over HTTP; G15
// covers the HTTPS names with `X-Forwarded-Proto: https`. The real src/api/client.ts and
// src/api/session.ts drive the Pact mock server; the Node test adds what a browser adds by itself
// (the `Cookie` header, `Sec-Fetch-Site`, `Origin`) through the client's `fetch` option. Written to
// build/pacts/storefront-gateway.json at the repository root for the gateway provider to verify.
const { eachLike, integer, like, regex } = MatchersV3;

const SIGN_IN = '/api/v1/identity/sessions';
const REFRESH = '/api/v1/identity/sessions/refresh';
const SIGN_OUT = '/api/v1/identity/sessions/current';
const PROFILE = '/api/v1/identity/accounts/me';
const CART = '/api/v1/cart';
const CART_LINES = '/api/v1/cart/lines';
const CART_MERGE = '/api/v1/cart/merge';
const TRACES = '/api/v1/telemetry/v1/traces';
const LOGS = '/api/v1/telemetry/v1/logs';
const PRODUCT_PATH = '/products/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21';

const PROBLEM = 'application/problem+json';
const SEALED = '[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+';
const SESSION_VALUE = 'k1.AAAAAAAAAAAAAAAA_session-example';
const CART_VALUE = 'k1.BBBBBBBBBBBBBBBB_cart-example';
const CART_MAX_AGE = '2592000';
const OTLP_LIMIT_BYTES = 256 * 1024;
const INSTANT_PATTERN =
  '^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$';
const STOREFRONT_CSP =
  "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; " +
  "connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; " +
  "form-action 'self'; object-src 'none'";

const STATE_ACCEPTS = 'identity accepts the credentials of ana@example.com and issues a token pair';
const STATE_SESSION = 'a session cookie for ana@example.com exists';
const STATE_CART = 'a cart cookie for token tok-cart-1 exists';
const STATE_BOTH =
  'a session cookie for ana@example.com and a cart cookie for token tok-cart-1 exist';
const STATE_COLLECTOR = 'the telemetry collector accepts OTLP';
const STATE_SHELL = 'the storefront upstream serves index.html';

const quote = (text: string) => text.replaceAll(/[.*+?^${}()|[\]\\]/g, '\\$&');

// Sealed cookie values are opaque to the storefront: only their shape is part of the contract.
const sessionCookie = regex(`^session=${SEALED}$`, `session=${SESSION_VALUE}`);
const cartCookie = regex(`^cart=${SEALED}$`, `cart=${CART_VALUE}`);
const bothCookies = regex(
  `^session=${SEALED}; cart=${SEALED}$`,
  `session=${SESSION_VALUE}; cart=${CART_VALUE}`,
);

// A `Set-Cookie` line: the pattern is its contract (sealed values vary, so only their shape is
// fixed), the example is what the mock server answers.
type CookieLine = { readonly pattern: string; readonly example: string };

const setSession = (name = 'session', secure = false): CookieLine => ({
  pattern: `${quote(name)}=${SEALED}; HttpOnly;${secure ? ' Secure;' : ''} SameSite=Strict; Path=/`,
  example: `${name}=${SESSION_VALUE}; HttpOnly;${secure ? ' Secure;' : ''} SameSite=Strict; Path=/`,
});
const setCart: CookieLine = {
  pattern: `cart=${SEALED}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${CART_MAX_AGE}`,
  example: `cart=${CART_VALUE}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${CART_MAX_AGE}`,
};
const deleted = (name: string, sameSite: string, secure = false): CookieLine => {
  const line = `${name}=; Max-Age=0; HttpOnly;${secure ? ' Secure;' : ''} SameSite=${sameSite}; Path=/`;
  return { pattern: quote(line), example: line };
};

// The gateway never sets both names of a cookie: when it sets one it also deletes the other
// (gateway-browser-session.yaml, "Cookie names"), so a set lists two lines; a deletion is one line,
// for the name the request arrived under.
const SESSION_SET = [setSession(), deleted('__Host-session', 'Strict', true)];
const SESSION_GONE = [deleted('session', 'Strict')];
const CART_SET = [setCart, deleted('__Host-cart', 'Lax', true)];
const CART_GONE = [deleted('cart', 'Lax')];

// The V4 response builder writes an array header to index 0 only (a matcher inside the array
// would be serialised as text), so several `Set-Cookie` lines go through the interaction itself,
// one index each, as the V3 builder does. Pact keeps one matching rule per header name, so every
// line is matched against the alternation of all of them.
type HeaderWriter = {
  withResponseHeader(name: string, index: number, value: string): unknown;
};
function setCookies(response: object, lines: readonly CookieLine[]): void {
  const { interaction } = response as unknown as { interaction: HeaderWriter };
  const anyLine = `^(?:${lines.map((line) => line.pattern).join('|')})$`;
  lines.forEach((line, index) => {
    interaction.withResponseHeader(
      'Set-Cookie',
      index,
      MatchersV3.matcherValueOrString(regex(anyLine, line.example)),
    );
  });
}

const instant = (example: string) => regex(INSTANT_PATTERN, example);
const role = () => regex('^(shopper|operator|admin)$', 'shopper');
const sessionSummary = { expiresAt: instant('2026-10-04T10:30:00Z'), roles: eachLike(role()) };

function problem(slug: string, status: number, title: string) {
  return {
    type: regex(`^.*/problems/${slug}$`, `https://ecommerce.example/problems/${slug}`),
    title: like(title),
    status,
  };
}

/** A `fetch` that adds the headers a browser adds on its own (never set by the client itself). */
function withHeaders(extra: Readonly<Record<string, string>>) {
  return async (request: Request): Promise<Response> => {
    for (const [name, value] of Object.entries(extra)) request.headers.set(name, value);
    return fetch(request);
  };
}

const gatewayClient = (baseUrl: string, extra: Readonly<Record<string, string>> = {}) =>
  createApiClient<GatewayPaths>({ baseUrl, fetch: withHeaders(extra) });
const cartClient = (baseUrl: string, extra: Readonly<Record<string, string>> = {}) =>
  createApiClient<CartPaths>({ baseUrl, fetch: withHeaders(extra) });
const telemetryClient = (baseUrl: string) => createApiClient<TelemetryPaths>({ baseUrl });
const sessionPort = (baseUrl: string, extra: Readonly<Record<string, string>> = {}) =>
  createSessionPort({ baseUrl, fetch: withHeaders(extra) });

const ana = (() => {
  const email = Email.parse('ana@example.com');
  const password = Password.forSignIn('S3cure-passphrase!');
  if (!email.ok || !password.ok) throw new Error('fixture credentials must be valid');
  return { email: email.value, password: password.value };
})();

const SIGN_IN_BODY = { email: 'ana@example.com', password: 'S3cure-passphrase!' };
const ADD_LINE = { productId: '0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21', quantity: 1 };
const BROWSER_SESSION = { 'X-Browser-Session': 'cookie' } as const;
// Sent by every browser on a same-origin write; the gateway refuses a cookie write without it.
const SAME_ORIGIN = { 'Sec-Fetch-Site': 'same-origin' } as const;
const emptyCart = { lines: [] };

const provider = pactFor('gateway');

describe('storefront → gateway pact, browser session (G1–G8, G15)', () => {
  it('G1 signs in and receives the tokenless session summary with the session cookie', async () => {
    await provider
      .addInteraction()
      .given(STATE_ACCEPTS)
      .uponReceiving('a sign-in in cookie mode')
      .withRequest('POST', SIGN_IN, (request) => {
        request.headers({ ...BROWSER_SESSION }).jsonBody(SIGN_IN_BODY);
      })
      .willRespondWith(200, (response) => {
        setCookies(response, SESSION_SET);
        response.jsonBody(sessionSummary);
      })
      .executeTest(async (mockServer) => {
        const summary = await sessionPort(mockServer.url).signIn(ana.email, ana.password);
        expect(summary.roles).toEqual(['shopper']);
        expect(Object.keys(summary).sort()).toEqual(['expiresAt', 'roles']);
      });
  });

  describe('G2 identity answers passed through without a cookie', () => {
    it('401 invalid credentials', async () => {
      await provider
        .addInteraction()
        .given('identity rejects the credentials of ana@example.com')
        .uponReceiving('a sign-in in cookie mode with wrong credentials')
        .withRequest('POST', SIGN_IN, (request) => {
          request.headers({ ...BROWSER_SESSION }).jsonBody(SIGN_IN_BODY);
        })
        .willRespondWith(401, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('unauthorized', 401, 'x'));
        })
        .executeTest(async (mockServer) => {
          await expect(
            sessionPort(mockServer.url).signIn(ana.email, ana.password),
          ).rejects.toBeInstanceOf(UnauthorizedError);
        });
    });

    it('403 unverified address', async () => {
      await provider
        .addInteraction()
        .given('identity reports ana@example.com as unverified')
        .uponReceiving('a sign-in in cookie mode of an unverified address')
        .withRequest('POST', SIGN_IN, (request) => {
          request.headers({ ...BROWSER_SESSION }).jsonBody(SIGN_IN_BODY);
        })
        .willRespondWith(403, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('forbidden', 403, 'x'));
        })
        .executeTest(async (mockServer) => {
          const failure = await sessionPort(mockServer.url)
            .signIn(ana.email, ana.password)
            .catch((error: unknown) => error);
          expect(failure).toBeInstanceOf(ProblemError);
          expect((failure as ProblemError).problem.type).toBe('forbidden');
        });
    });

    it('429 throttled with Retry-After', async () => {
      await provider
        .addInteraction()
        .given('identity throttles ana@example.com')
        .uponReceiving('a sign-in in cookie mode while throttled')
        .withRequest('POST', SIGN_IN, (request) => {
          request.headers({ ...BROWSER_SESSION }).jsonBody(SIGN_IN_BODY);
        })
        .willRespondWith(429, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM, 'Retry-After': regex('^\\d+$', '42') })
            .jsonBody(problem('throttled', 429, 'x'));
        })
        .executeTest(async (mockServer) => {
          const failure = await sessionPort(mockServer.url)
            .signIn(ana.email, ana.password)
            .catch((error: unknown) => error);
          expect(failure).toBeInstanceOf(ThrottledError);
          expect((failure as ThrottledError).retryAfterSeconds).toBe(42);
        });
    });
  });

  describe('G3 explicit renewal', () => {
    it('200 session summary and a re-set cookie', async () => {
      await provider
        .addInteraction()
        .given('a session cookie for ana@example.com exists and identity rotates refresh tokens')
        .uponReceiving('an explicit session renewal')
        .withRequest('POST', REFRESH, (request) => {
          request.headers({ ...BROWSER_SESSION, ...SAME_ORIGIN, Cookie: sessionCookie });
        })
        .willRespondWith(200, (response) => {
          setCookies(response, SESSION_SET);
          response.jsonBody(sessionSummary);
        })
        .executeTest(async (mockServer) => {
          const { data, response } = await gatewayClient(mockServer.url, {
            ...SAME_ORIGIN,
            Cookie: `session=${SESSION_VALUE}`,
          }).POST(REFRESH, { params: { header: BROWSER_SESSION } });
          expect(data?.roles).toEqual(['shopper']);
          expect(response.headers.getSetCookie()).toHaveLength(2);
        });
    });

    it('401 and cookie deletion when identity refuses the refresh', async () => {
      await provider
        .addInteraction()
        .given('identity rejects the refresh token')
        .uponReceiving('an explicit session renewal refused by identity')
        .withRequest('POST', REFRESH, (request) => {
          request.headers({ ...BROWSER_SESSION, ...SAME_ORIGIN, Cookie: sessionCookie });
        })
        .willRespondWith(401, (response) => {
          setCookies(response, SESSION_GONE);
          response
            .headers({
              'Content-Type': PROBLEM,
            })
            .jsonBody(problem('unauthorized', 401, 'x'));
        })
        .executeTest(async (mockServer) => {
          await expect(
            gatewayClient(mockServer.url, {
              ...SAME_ORIGIN,
              Cookie: `session=${SESSION_VALUE}`,
            }).POST(REFRESH, {
              params: { header: BROWSER_SESSION },
            }),
          ).rejects.toBeInstanceOf(UnauthorizedError);
        });
    });
  });

  describe('G4–G7 cookie-authenticated requests (the page-load session probe)', () => {
    it('G4 signed in: the response re-sets the cookie', async () => {
      await provider
        .addInteraction()
        .given(STATE_SESSION)
        .uponReceiving('a cookie-authenticated profile request')
        .withRequest('GET', PROFILE, (request) => {
          request.headers({ Cookie: sessionCookie });
        })
        .willRespondWith(200, (response) => {
          setCookies(response, SESSION_SET);
          response.jsonBody({ roles: eachLike(role()) });
        })
        .executeTest(async (mockServer) => {
          const probe = await sessionPort(mockServer.url, {
            Cookie: `session=${SESSION_VALUE}`,
          }).probe();
          expect(probe).toEqual({ kind: 'signedIn', roles: ['shopper'] });
        });
    });

    it('G5 access token about to expire: refreshed first, then forwarded', async () => {
      await provider
        .addInteraction()
        .given(
          'a session cookie for ana@example.com exists whose access token expires in 30 seconds',
        )
        .uponReceiving(
          'a cookie-authenticated profile request with an access token about to expire',
        )
        .withRequest('GET', PROFILE, (request) => {
          request.headers({ Cookie: sessionCookie });
        })
        .willRespondWith(200, (response) => {
          setCookies(response, SESSION_SET);
          response.jsonBody({ roles: eachLike(role()) });
        })
        .executeTest(async (mockServer) => {
          const probe = await sessionPort(mockServer.url, {
            Cookie: `session=${SESSION_VALUE}`,
          }).probe();
          expect(probe.kind).toBe('signedIn');
        });
    });

    it('G6 idle for more than 30 minutes: 401 and cookie deletion', async () => {
      await provider
        .addInteraction()
        .given('a session cookie for ana@example.com exists whose last activity was 31 minutes ago')
        .uponReceiving('a cookie-authenticated profile request after 31 idle minutes')
        .withRequest('GET', PROFILE, (request) => {
          request.headers({ Cookie: sessionCookie });
        })
        .willRespondWith(401, (response) => {
          setCookies(response, SESSION_GONE);
          response
            .headers({
              'Content-Type': PROBLEM,
            })
            .jsonBody(problem('unauthorized', 401, 'x'));
        })
        .executeTest(async (mockServer) => {
          const probe = await sessionPort(mockServer.url, {
            Cookie: `session=${SESSION_VALUE}`,
          }).probe();
          expect(probe).toEqual({ kind: 'anonymous' });
        });
    });

    it('G7 tampered or unsealable cookie: 401 and cookie deletion', async () => {
      await provider
        .addInteraction()
        .given('a session cookie that cannot be unsealed exists')
        .uponReceiving('a cookie-authenticated profile request with an unsealable cookie')
        .withRequest('GET', PROFILE, (request) => {
          request.headers({ Cookie: sessionCookie });
        })
        .willRespondWith(401, (response) => {
          setCookies(response, SESSION_GONE);
          response
            .headers({
              'Content-Type': PROBLEM,
            })
            .jsonBody(problem('unauthorized', 401, 'x'));
        })
        .executeTest(async (mockServer) => {
          const probe = await sessionPort(mockServer.url, {
            Cookie: `session=${SESSION_VALUE}`,
          }).probe();
          expect(probe).toEqual({ kind: 'anonymous' });
        });
    });
  });

  it('G8 signs out: 204 and the cookie deleted', async () => {
    await provider
      .addInteraction()
      .given(STATE_SESSION)
      .uponReceiving('a sign-out in cookie mode')
      .withRequest('DELETE', SIGN_OUT, (request) => {
        request.headers({ ...BROWSER_SESSION, ...SAME_ORIGIN, Cookie: sessionCookie });
      })
      .willRespondWith(204, (response) => {
        setCookies(response, SESSION_GONE);
        response.headers({});
      })
      .executeTest(async (mockServer) => {
        await expect(
          sessionPort(mockServer.url, {
            ...SAME_ORIGIN,
            Cookie: `session=${SESSION_VALUE}`,
          }).signOut(),
        ).resolves.toBeUndefined();
      });
  });

  it('G15 signs in over HTTPS: the __Host- cookie is set and the plain name deleted', async () => {
    await provider
      .addInteraction()
      .given(STATE_ACCEPTS)
      .uponReceiving('a sign-in in cookie mode over HTTPS')
      .withRequest('POST', SIGN_IN, (request) => {
        request
          .headers({ ...BROWSER_SESSION, 'X-Forwarded-Proto': 'https' })
          .jsonBody(SIGN_IN_BODY);
      })
      .willRespondWith(200, (response) => {
        setCookies(response, [setSession('__Host-session', true), deleted('session', 'Strict')]);
        response.jsonBody(sessionSummary);
      })
      .executeTest(async (mockServer) => {
        const summary = await sessionPort(mockServer.url, { 'X-Forwarded-Proto': 'https' }).signIn(
          ana.email,
          ana.password,
        );
        expect(summary.roles).toEqual(['shopper']);
      });
  });
});

describe('storefront → gateway pact, cross-site protection and credentials (G9, G10, G14)', () => {
  describe('G9 non-GET with the session cookie', () => {
    it.each([
      ['Sec-Fetch-Site: cross-site', { 'Sec-Fetch-Site': 'cross-site' }],
      ['a mismatching Origin', { Origin: 'https://evil.example' }],
    ])('403 forbidden for %s', async (label, browser) => {
      await provider
        .addInteraction()
        .given(STATE_SESSION)
        .uponReceiving(`a cart write with the session cookie and ${label}`)
        .withRequest('POST', CART_LINES, (request) => {
          request
            .headers({ ...BROWSER_SESSION, Cookie: sessionCookie, ...browser })
            .jsonBody(ADD_LINE);
        })
        .willRespondWith(403, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('forbidden', 403, 'x'));
        })
        .executeTest(async (mockServer) => {
          const failure = await cartClient(mockServer.url, {
            Cookie: `session=${SESSION_VALUE}`,
            ...browser,
          })
            .POST(CART_LINES, { body: ADD_LINE })
            .catch((error: unknown) => error);
          expect(failure).toBeInstanceOf(ProblemError);
          expect((failure as ProblemError).problem.type).toBe('forbidden');
        });
    });

    it.each(['same-origin', 'none'])('is allowed for Sec-Fetch-Site: %s', async (site) => {
      await provider
        .addInteraction()
        .given(STATE_SESSION)
        .uponReceiving(`a cart write with the session cookie and Sec-Fetch-Site ${site}`)
        .withRequest('POST', CART_LINES, (request) => {
          request
            .headers({ ...BROWSER_SESSION, Cookie: sessionCookie, 'Sec-Fetch-Site': site })
            .jsonBody(ADD_LINE);
        })
        .willRespondWith(201, (response) => {
          setCookies(response, SESSION_SET);
          response.jsonBody({ lines: [] });
        })
        .executeTest(async (mockServer) => {
          const { data } = await cartClient(mockServer.url, {
            Cookie: `session=${SESSION_VALUE}`,
            'Sec-Fetch-Site': site,
          }).POST(CART_LINES, { body: ADD_LINE });
          expect(data?.lines).toEqual([]);
        });
    });
  });

  it('G10 refuses a request carrying both the cookie and an Authorization header', async () => {
    await provider
      .addInteraction()
      .given(STATE_SESSION)
      .uponReceiving('a request with the session cookie and its own Authorization header')
      .withRequest('GET', PROFILE, (request) => {
        request.headers({ Cookie: sessionCookie, Authorization: 'Basic YW5hOnMzY3VyZQ==' });
      })
      .willRespondWith(400, (response) => {
        response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('validation', 400, 'x'));
      })
      .executeTest(async (mockServer) => {
        const failure = await createApiClient<GatewayPaths>({
          baseUrl: mockServer.url,
          fetch: withHeaders({
            Cookie: `session=${SESSION_VALUE}`,
            Authorization: 'Basic YW5hOnMzY3VyZQ==',
          }),
        })
          .GET(PROFILE)
          .catch((error: unknown) => error);
        expect(failure).toBeInstanceOf(ProblemError);
        expect((failure as ProblemError).problem.type).toBe('validation');
      });
  });

  it('G14 forwards an anonymous cart write without an origin check', async () => {
    await provider
      .addInteraction()
      .given('no cookies exist')
      .uponReceiving('an anonymous cart write that looks cross-site')
      .withRequest('POST', CART_LINES, (request) => {
        request
          .headers({
            ...BROWSER_SESSION,
            'Sec-Fetch-Site': 'cross-site',
            Origin: 'https://other.example',
          })
          .jsonBody(ADD_LINE);
      })
      .willRespondWith(201, (response) => {
        response.jsonBody(emptyCart);
      })
      .executeTest(async (mockServer) => {
        const { data } = await cartClient(mockServer.url, {
          'Sec-Fetch-Site': 'cross-site',
          Origin: 'https://other.example',
        }).POST(CART_LINES, { body: ADD_LINE });
        expect(data?.lines).toEqual([]);
      });
  });
});

describe('storefront → gateway pact, anonymous cart (G11–G13)', () => {
  it('G11 the first anonymous write sets the cart cookie', async () => {
    await provider
      .addInteraction()
      .given('no anonymous cart exists')
      .uponReceiving('the first write to an anonymous cart')
      .withRequest('POST', CART_LINES, (request) => {
        request.headers({ ...BROWSER_SESSION }).jsonBody(ADD_LINE);
      })
      .willRespondWith(201, (response) => {
        setCookies(response, CART_SET);
        response.jsonBody(emptyCart);
      })
      .executeTest(async (mockServer) => {
        const { response } = await cartClient(mockServer.url).POST(CART_LINES, { body: ADD_LINE });
        expect(response.headers.get('X-Cart-Token')).toBeNull();
        expect(response.headers.getSetCookie()[0]).toContain('cart=');
      });
  });

  describe('G12 cart reads with the cart cookie', () => {
    it('the cookie alone identifies the cart', async () => {
      await provider
        .addInteraction()
        .given(STATE_CART)
        .uponReceiving('a cart read with the cart cookie and no cart token')
        .withRequest('GET', CART, (request) => {
          request.headers({ ...BROWSER_SESSION, Cookie: cartCookie });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(emptyCart);
        })
        .executeTest(async (mockServer) => {
          const { data } = await gatewayClient(mockServer.url, {
            Cookie: `cart=${CART_VALUE}`,
          }).GET(CART, { params: { header: BROWSER_SESSION } });
          expect(data?.lines).toEqual([]);
        });
    });

    it('an explicit X-Cart-Token is not overridden', async () => {
      await provider
        .addInteraction()
        .given(STATE_CART)
        .uponReceiving('a cart read with the cart cookie and an explicit cart token')
        .withRequest('GET', CART, (request) => {
          request.headers({
            ...BROWSER_SESSION,
            Cookie: cartCookie,
            'X-Cart-Token': 'tok-explicit-9',
          });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(emptyCart);
        })
        .executeTest(async (mockServer) => {
          const { data } = await cartClient(mockServer.url, {
            Cookie: `cart=${CART_VALUE}`,
          }).GET(CART, {
            params: { header: { 'X-Cart-Token': 'tok-explicit-9' } },
          });
          expect(data?.lines).toEqual([]);
        });
    });
  });

  describe('G13 merging the anonymous cart after sign-in', () => {
    it('200 deletes the cart cookie', async () => {
      await provider
        .addInteraction()
        .given(STATE_BOTH)
        .uponReceiving('a cart merge with both cookies')
        .withRequest('POST', CART_MERGE, (request) => {
          request.headers({ ...BROWSER_SESSION, ...SAME_ORIGIN, Cookie: bothCookies });
        })
        .willRespondWith(200, (response) => {
          setCookies(response, [...SESSION_SET, ...CART_GONE]);
          response.jsonBody({ cappedLines: [] });
        })
        .executeTest(async (mockServer) => {
          const { data, response } = await gatewayClient(mockServer.url, {
            ...SAME_ORIGIN,
            Cookie: `session=${SESSION_VALUE}; cart=${CART_VALUE}`,
          }).POST(CART_MERGE, { params: { header: BROWSER_SESSION } });
          expect(data?.cappedLines).toEqual([]);
          expect(response.headers.getSetCookie()).toContain(deleted('cart', 'Lax').example);
        });
    });

    it('409 keeps the cart cookie so the merge can be retried', async () => {
      await provider
        .addInteraction()
        .given('a merge for tok-cart-1 is in progress')
        .uponReceiving('a cart merge while another merge of the same cart is in progress')
        .withRequest('POST', CART_MERGE, (request) => {
          request.headers({ ...BROWSER_SESSION, ...SAME_ORIGIN, Cookie: bothCookies });
        })
        .willRespondWith(409, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('conflict', 409, 'x'));
        })
        .executeTest(async (mockServer) => {
          const failure = await gatewayClient(mockServer.url, {
            ...SAME_ORIGIN,
            Cookie: `session=${SESSION_VALUE}; cart=${CART_VALUE}`,
          })
            .POST(CART_MERGE, { params: { header: BROWSER_SESSION } })
            .catch((error: unknown) => error);
          expect(failure).toBeInstanceOf(ProblemError);
          expect((failure as ProblemError).problem.type).toBe('conflict');
        });
    });
  });
});

describe('storefront → gateway pact, page shell and deny by default (G18, G19)', () => {
  it.each(['/', PRODUCT_PATH])('G18 serves the HTML shell for GET %s', async (path) => {
    await provider
      .addInteraction()
      .given(STATE_SHELL)
      .uponReceiving(`a page load of ${path}`)
      .withRequest('GET', path, (request) => {
        request.headers({ Accept: 'text/html' });
      })
      .willRespondWith(200, (response) => {
        response.headers({
          'Content-Type': regex('^text/html(;.*)?$', 'text/html;charset=UTF-8'),
          'Content-Security-Policy': regex(`^${quote(STOREFRONT_CSP)}$`, STOREFRONT_CSP),
          'X-Frame-Options': 'DENY',
          'Referrer-Policy': 'no-referrer',
          'Cache-Control': 'no-store',
        });
      })
      .executeTest(async (mockServer) => {
        const page = await fetch(`${mockServer.url}${path}`, { headers: { Accept: 'text/html' } });
        expect(page.status).toBe(200);
        expect(page.headers.get('Content-Security-Policy')).not.toContain('unsafe-inline');
      });
  });

  it.each([
    ['GET', '/api/v1/unknown'],
    ['POST', '/products/1'],
  ])('G19 answers 404 not-found for %s %s, never the shell', async (method, path) => {
    await provider
      .addInteraction()
      .given(STATE_SHELL)
      .uponReceiving(`${method} ${path} on a route nobody declared`)
      .withRequest(method, path)
      .willRespondWith(404, (response) => {
        response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('not-found', 404, 'x'));
      })
      .executeTest(async (mockServer) => {
        const answer = await fetch(`${mockServer.url}${path}`, { method });
        expect(answer.status).toBe(404);
        expect(answer.headers.get('Content-Type')).toContain('problem+json');
      });
  });
});

// The storefront telemetry module (src/telemetry) is implemented in a later task; until then the
// typed generated client of the telemetry contract plays the exporter. The throttled interaction
// comes last: it exhausts the `browse` budget of the replaying client for the rest of the minute.
// The generated OTLP request type is a loose index signature; the payloads below are real OTLP/JSON.
type OtlpBody = NonNullable<
  TelemetryPaths[typeof TRACES]['post']['requestBody']
>['content']['application/json'];
const otlp = (body: object) => body as OtlpBody;

const spans = otlp({
  resourceSpans: [
    {
      resource: {
        attributes: [{ key: 'service.name', value: { stringValue: 'storefront' } }],
      },
      scopeSpans: [],
    },
  ],
});
const logs = otlp({
  resourceLogs: [
    {
      resource: {
        attributes: [{ key: 'service.name', value: { stringValue: 'storefront' } }],
      },
      scopeLogs: [],
    },
  ],
});
const oversized = otlp({
  resourceLogs: [
    {
      scopeLogs: [{ logRecords: [{ body: { stringValue: 'x'.repeat(OTLP_LIMIT_BYTES + 1024) } }] }],
    },
  ],
});

describe('storefront → gateway pact, telemetry (G16, G17)', () => {
  it('G16 forwards traces and returns the collector body', async () => {
    await provider
      .addInteraction()
      .given(STATE_COLLECTOR)
      .uponReceiving('an anonymous export of browser traces')
      .withRequest('POST', TRACES, (request) => {
        request.jsonBody(spans);
      })
      .willRespondWith(200, (response) => {
        response.jsonBody({});
      })
      .executeTest(async (mockServer) => {
        const { response } = await telemetryClient(mockServer.url).POST(TRACES, { body: spans });
        expect(response.status).toBe(200);
      });
  });

  it('G16 forwards logs and returns the collector partial-success body', async () => {
    await provider
      .addInteraction()
      .given(STATE_COLLECTOR)
      .uponReceiving('an anonymous export of browser logs')
      .withRequest('POST', LOGS, (request) => {
        request.jsonBody(logs);
      })
      .willRespondWith(200, (response) => {
        response.jsonBody({ partialSuccess: { rejectedLogRecords: integer(0) } });
      })
      .executeTest(async (mockServer) => {
        const { data } = await telemetryClient(mockServer.url).POST(LOGS, { body: logs });
        expect(data?.partialSuccess?.rejectedLogRecords).toBe(0);
      });
  });

  it('G17 refuses a body above 256 KiB with 413 payload-too-large', async () => {
    await provider
      .addInteraction()
      .given(STATE_COLLECTOR)
      .uponReceiving('an export of browser logs above 256 KiB')
      .withRequest('POST', LOGS, (request) => {
        request.jsonBody(oversized);
      })
      .willRespondWith(413, (response) => {
        response
          .headers({ 'Content-Type': PROBLEM })
          .jsonBody(problem('payload-too-large', 413, 'x'));
      })
      .executeTest(async (mockServer) => {
        const failure = await telemetryClient(mockServer.url)
          .POST(LOGS, { body: oversized })
          .catch((error: unknown) => error);
        expect(failure).toBeInstanceOf(ProblemError);
        expect((failure as ProblemError).problem.type).toBe('payload-too-large');
      });
  });

  it('G17 answers 503 unavailable when the collector is not running', async () => {
    await provider
      .addInteraction()
      .given('the telemetry collector is not running')
      .uponReceiving('an export of browser traces while the collector is down')
      .withRequest('POST', TRACES, (request) => {
        request.jsonBody(spans);
      })
      .willRespondWith(503, (response) => {
        response.headers({ 'Content-Type': PROBLEM }).jsonBody(problem('unavailable', 503, 'x'));
      })
      .executeTest(async (mockServer) => {
        const failure = await telemetryClient(mockServer.url)
          .POST(TRACES, { body: spans })
          .catch((error: unknown) => error);
        expect(failure).toBeInstanceOf(ProblemError);
        expect((failure as ProblemError).problem.type).toBe('unavailable');
      });
  });

  it('G17 answers 429 throttled with Retry-After once the browse budget is exhausted', async () => {
    await provider
      .addInteraction()
      .given('the browse budget of the client is exhausted')
      .uponReceiving('throttled export of browser logs once the browse budget is exhausted')
      .withRequest('POST', LOGS, (request) => {
        request.jsonBody(logs);
      })
      .willRespondWith(429, (response) => {
        response
          .headers({ 'Content-Type': PROBLEM, 'Retry-After': regex('^\\d+$', '30') })
          .jsonBody(problem('throttled', 429, 'x'));
      })
      .executeTest(async (mockServer) => {
        const failure = await telemetryClient(mockServer.url)
          .POST(LOGS, { body: logs })
          .catch((error: unknown) => error);
        expect(failure).toBeInstanceOf(ThrottledError);
        expect((failure as ThrottledError).retryAfterSeconds).toBe(30);
      });
  });
});
