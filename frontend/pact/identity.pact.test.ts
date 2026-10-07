import { MatchersV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';

import { createIdentityApi } from '@api/identity';
import { ProblemError, ThrottledError, UnauthorizedError } from '@api/problem';
import type { Address } from '@domain/address';
import { Email } from '@domain/email';
import { Password } from '@domain/password';

import {
  ADA,
  ADDRESS_2,
  ADDRESS_OWNED,
  asGateway,
  bearer,
  instant,
  problem,
  PROBLEM,
  uuid,
} from './gateway.ts';
import { pactFor } from './pact.config.ts';

// Storefront → identity consumer pact: the interactions of user story 2 (I1–I5, I7, I10, I11 of
// contracts/pact-matrix.md; the remaining rows are added by later stories), provider states
// verbatim, driving the real src/api/identity.ts. Identity sees the request as the gateway
// forwards it: sign-in and refresh carry their token bodies (the gateway turns the answer into the
// cookie summary; only the request shape is asserted here), protected calls carry the bearer.
const { boolean, eachLike, integer, like, string } = MatchersV3;

const ACCOUNTS = '/api/v1/identity/accounts';
const VERIFY = '/api/v1/identity/accounts/verify-email';
const SESSIONS = '/api/v1/identity/sessions';
const REFRESH = '/api/v1/identity/sessions/refresh';
const ME = '/api/v1/identity/accounts/me';
const ADDRESSES = '/api/v1/identity/accounts/me/addresses';
const GENERIC_MESSAGE = 'If the address can be registered, a verification message has been sent.';

const ana = parsedEmail('ana@example.com');
const newcomer = parsedEmail('new@example.com');
const passphrase = parsedPassword('S3cure-passphrase!');

function parsedEmail(raw: string): Email {
  const parsed = Email.parse(raw);
  if (!parsed.ok) throw new Error(`fixture email ${raw}`);
  return parsed.value;
}

function parsedPassword(raw: string): Password {
  const parsed = Password.create(raw);
  if (!parsed.ok) throw new Error('fixture password');
  return parsed.value;
}

const tokenPair = {
  accessToken: like('eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIuLi4ifQ.signature'),
  refreshToken: like('9b8d6c1a-opaque-refresh-token'),
  tokenType: 'Bearer',
  expiresIn: integer(900),
};

const account = {
  id: uuid(ADA),
  email: string('ana@example.com'),
  displayName: like('Ana Silva'),
  emailVerified: boolean(true),
  roles: eachLike('shopper'),
  createdAt: instant('2026-10-02T09:15:00Z'),
};

const home: Address = {
  recipientName: 'Ana Silva',
  line1: 'Rua das Flores 12',
  city: 'Lisboa',
  postalCode: '1000-001',
  countryCode: 'PT',
  label: 'Home',
  isDefault: true,
};

function address(id: string) {
  return {
    id: uuid(id),
    label: like('Home'),
    recipientName: string('Ana Silva'),
    line1: string('Rua das Flores 12'),
    city: string('Lisboa'),
    postalCode: string('1000-001'),
    countryCode: string('PT'),
    isDefault: boolean(true),
  };
}

const provider = pactFor('identity');

const anonymous = (url: string) => createIdentityApi({ baseUrl: url, fetch: asGateway() });
const signedIn = (url: string) =>
  createIdentityApi({ baseUrl: url, fetch: asGateway({ bearer: true }) });

describe('storefront → identity pact (I1–I5, I7, I10, I11)', () => {
  describe('I1 signIn', () => {
    it('signs in with email and password; the gateway turns the token pair into the cookie summary', async () => {
      await provider
        .addInteraction()
        .given(
          'an account ana@example.com exists with a verified email and password S3cure-passphrase!',
        )
        .uponReceiving('a sign-in of ana@example.com with the right password')
        .withRequest('POST', SESSIONS, (request) => {
          request.jsonBody({ email: 'ana@example.com', password: 'S3cure-passphrase!' });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(tokenPair);
        })
        .executeTest(async (mockServer) => {
          await expect(anonymous(mockServer.url).signIn(ana, passphrase)).resolves.toBeDefined();
        });
    });
  });

  describe('I2 signIn refusals', () => {
    it('answers 401 for invalid credentials, without saying which part is wrong', async () => {
      await provider
        .addInteraction()
        .given(
          'an account ana@example.com exists with a verified email and password S3cure-passphrase!',
        )
        .uponReceiving('a sign-in of ana@example.com with a wrong password')
        .withRequest('POST', SESSIONS, (request) => {
          request.jsonBody({ email: 'ana@example.com', password: 'wrong-passphrase!' });
        })
        .willRespondWith(401, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('unauthorized', 401, 'Unauthorized', 'Invalid credentials.'));
        })
        .executeTest(async (mockServer) => {
          await expect(
            anonymous(mockServer.url).signIn(ana, parsedPassword('wrong-passphrase!')),
          ).rejects.toBeInstanceOf(UnauthorizedError);
        });
    });

    it('answers 403 for an unverified email', async () => {
      await provider
        .addInteraction()
        .given('an account ana@example.com exists with an unverified email')
        .uponReceiving('a sign-in of the unverified account ana@example.com')
        .withRequest('POST', SESSIONS, (request) => {
          request.jsonBody({ email: 'ana@example.com', password: 'S3cure-passphrase!' });
        })
        .willRespondWith(403, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('forbidden', 403, 'Forbidden', 'Email address is not verified.'));
        })
        .executeTest(async (mockServer) => {
          const failure = anonymous(mockServer.url).signIn(ana, passphrase);
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
          await failure.catch((error: unknown) => {
            expect((error as ProblemError).problem.status).toBe(403);
            expect((error as ProblemError).problem.type).toBe('forbidden');
          });
        });
    });

    it('answers 429 with Retry-After after five failed sign-ins', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com has 5 failed sign-ins')
        .uponReceiving('a sign-in of the throttled account ana@example.com')
        .withRequest('POST', SESSIONS, (request) => {
          request.jsonBody({ email: 'ana@example.com', password: 'S3cure-passphrase!' });
        })
        .willRespondWith(429, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM, 'Retry-After': '60' })
            .jsonBody(
              problem(
                'throttled',
                429,
                'Too many requests',
                'Too many failed sign-in attempts. Try again later.',
              ),
            );
        })
        .executeTest(async (mockServer) => {
          const failure = anonymous(mockServer.url).signIn(ana, passphrase);
          await expect(failure).rejects.toBeInstanceOf(ThrottledError);
          await failure.catch((error: unknown) => {
            expect((error as ThrottledError).retryAfterSeconds).toBe(60);
          });
        });
    });
  });

  describe('I3 registerAccount', () => {
    it('accepts a new email with the generic message', async () => {
      await provider
        .addInteraction()
        .given('no account exists for new@example.com')
        .uponReceiving('a registration of new@example.com')
        .withRequest('POST', ACCOUNTS, (request) => {
          request.jsonBody({ email: 'new@example.com', password: 'S3cure-passphrase!' });
        })
        .willRespondWith(202, (response) => {
          response.jsonBody({ message: string(GENERIC_MESSAGE) });
        })
        .executeTest(async (mockServer) => {
          expect(await anonymous(mockServer.url).register(newcomer, passphrase)).toBe(
            GENERIC_MESSAGE,
          );
        });
    });

    it('answers the same generic message for an already registered email', async () => {
      await provider
        .addInteraction()
        .given('an account ana@example.com exists with a verified email')
        .uponReceiving('a registration of the already registered ana@example.com')
        .withRequest('POST', ACCOUNTS, (request) => {
          request.jsonBody({ email: 'ana@example.com', password: 'S3cure-passphrase!' });
        })
        .willRespondWith(202, (response) => {
          response.jsonBody({ message: string(GENERIC_MESSAGE) });
        })
        .executeTest(async (mockServer) => {
          expect(await anonymous(mockServer.url).register(ana, passphrase)).toBe(GENERIC_MESSAGE);
        });
    });

    it('reports a password policy violation next to the field', async () => {
      await provider
        .addInteraction()
        .given('no account exists for new@example.com')
        .uponReceiving('a registration of new@example.com with a password equal to the email')
        .withRequest('POST', ACCOUNTS, (request) => {
          request.jsonBody({ email: 'new@example.com', password: 'new@example.com' });
        })
        .willRespondWith(422, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem(
              'validation',
              422,
              'Validation failed',
              'Password does not meet the policy.',
            ),
            errors: eachLike({
              field: string('password'),
              message: like('must not be equal to the email'),
            }),
          });
        })
        .executeTest(async (mockServer) => {
          const failure = anonymous(mockServer.url).register(
            newcomer,
            parsedPassword('new@example.com'),
          );
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
          await failure.catch((error: unknown) => {
            expect((error as ProblemError).problem.errors).toEqual([
              { field: 'password', message: 'must not be equal to the email' },
            ]);
          });
        });
    });
  });

  describe('I4 verifyEmail', () => {
    it('verifies a pending token', async () => {
      await provider
        .addInteraction()
        .given('a pending verification token tok-valid exists')
        .uponReceiving('a verification with the token tok-valid')
        .withRequest('POST', VERIFY, (request) => {
          request.jsonBody({ token: 'tok-valid' });
        })
        .willRespondWith(204)
        .executeTest(async (mockServer) => {
          await expect(anonymous(mockServer.url).verifyEmail('tok-valid')).resolves.toBeUndefined();
        });
    });

    it('refuses an expired token', async () => {
      await provider
        .addInteraction()
        .given('the verification token tok-expired is expired')
        .uponReceiving('a verification with the expired token tok-expired')
        .withRequest('POST', VERIFY, (request) => {
          request.jsonBody({ token: 'tok-expired' });
        })
        .willRespondWith(422, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(
              problem('validation', 422, 'Validation failed', 'The token is invalid or expired.'),
            );
        })
        .executeTest(async (mockServer) => {
          await expect(anonymous(mockServer.url).verifyEmail('tok-expired')).rejects.toBeInstanceOf(
            ProblemError,
          );
        });
    });
  });

  describe('I5 refreshSession', () => {
    it('exchanges the refresh token the gateway supplies from the cookie', async () => {
      await provider
        .addInteraction()
        .given('an account ana@example.com has a valid refresh token')
        .uponReceiving('a refresh of the session of ana@example.com')
        .withRequest('POST', REFRESH, (request) => {
          request.jsonBody({ refreshToken: like('9b8d6c1a-opaque-refresh-token') });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(tokenPair);
        })
        .executeTest(async (mockServer) => {
          const identity = createIdentityApi({
            baseUrl: mockServer.url,
            fetch: asGateway({ refreshToken: '9b8d6c1a-opaque-refresh-token' }),
          });
          await expect(identity.refresh()).resolves.toBeDefined();
        });
    });
  });

  describe('I7 getOwnProfile', () => {
    it('reads the signed-in account with its roles and verification', async () => {
      await provider
        .addInteraction()
        .given('an account ana@example.com is signed in')
        .uponReceiving('a read of the own profile of ana@example.com')
        .withRequest('GET', ME, (request) => {
          request.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody(account);
        })
        .executeTest(async (mockServer) => {
          const profile = await signedIn(mockServer.url).getOwnProfile();
          expect(profile.email).toBe('ana@example.com');
          expect(profile.roles).toEqual(['shopper']);
          expect(profile.emailVerified).toBe(true);
        });
    });

    it('answers 401 without a valid token (the page-load probe resolves to anonymous)', async () => {
      await provider
        .addInteraction()
        .given('no valid token')
        .uponReceiving('a read of the own profile without a token')
        .withRequest('GET', ME)
        .willRespondWith(401, (response) => {
          response
            .headers({ 'Content-Type': PROBLEM })
            .jsonBody(problem('unauthorized', 401, 'Unauthorized', 'Missing credentials.'));
        })
        .executeTest(async (mockServer) => {
          await expect(anonymous(mockServer.url).getOwnProfile()).rejects.toBeInstanceOf(
            UnauthorizedError,
          );
        });
    });
  });

  describe('I10 listOwnAddresses', () => {
    it('lists the saved addresses', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com has 2 addresses')
        .uponReceiving('a read of the addresses of ana@example.com')
        .withRequest('GET', ADDRESSES, (request) => {
          request.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: eachLike(address(ADDRESS_2), 2),
            page: integer(0),
            size: integer(20),
            totalItems: integer(2),
          });
        })
        .executeTest(async (mockServer) => {
          const page = await signedIn(mockServer.url).listOwnAddresses();
          expect(page.totalItems).toBe(2);
          expect(page.items[0]?.id).toBe(ADDRESS_2);
          expect(page.items[0]?.countryCode).toBe('PT');
        });
    });

    it('answers an empty page when no address is saved', async () => {
      await provider
        .addInteraction()
        .given('ana@example.com has no addresses')
        .uponReceiving('a read of the addresses of ana@example.com when there are none')
        .withRequest('GET', ADDRESSES, (request) => {
          request.headers({ Authorization: bearer() });
        })
        .willRespondWith(200, (response) => {
          response.jsonBody({
            items: [],
            page: integer(0),
            size: integer(20),
            totalItems: integer(0),
          });
        })
        .executeTest(async (mockServer) => {
          const page = await signedIn(mockServer.url).listOwnAddresses();
          expect(page.items).toEqual([]);
        });
    });
  });

  describe('I11 addOwnAddress', () => {
    it('saves a new address and answers it with its id', async () => {
      await provider
        .addInteraction()
        .given('an account ana@example.com is signed in')
        .uponReceiving('a new delivery address of ana@example.com')
        .withRequest('POST', ADDRESSES, (request) => {
          request.headers({ Authorization: bearer() }).jsonBody({
            label: 'Home',
            recipientName: 'Ana Silva',
            line1: 'Rua das Flores 12',
            city: 'Lisboa',
            postalCode: '1000-001',
            countryCode: 'PT',
            isDefault: true,
          });
        })
        .willRespondWith(201, (response) => {
          response.jsonBody(address(ADDRESS_OWNED));
        })
        .executeTest(async (mockServer) => {
          const saved = await signedIn(mockServer.url).addOwnAddress(home);
          expect(saved.id).toBe(ADDRESS_OWNED);
          expect(saved.city).toBe('Lisboa');
        });
    });

    it('reports per-field errors for an address outside the bounds', async () => {
      await provider
        .addInteraction()
        .given('an account ana@example.com is signed in')
        .uponReceiving('a new delivery address of ana@example.com with a recipient name too long')
        .withRequest('POST', ADDRESSES, (request) => {
          request.headers({ Authorization: bearer() }).jsonBody({
            recipientName: 'A'.repeat(101),
            line1: 'Rua das Flores 12',
            city: 'Lisboa',
            postalCode: '1000-001',
            countryCode: 'PT',
            isDefault: false,
          });
        })
        .willRespondWith(422, (response) => {
          response.headers({ 'Content-Type': PROBLEM }).jsonBody({
            ...problem('validation', 422, 'Validation failed', 'The address is not valid.'),
            errors: eachLike({
              field: string('recipientName'),
              message: like('must be at most 100 characters'),
            }),
          });
        })
        .executeTest(async (mockServer) => {
          // Built without the domain parser on purpose: the bounds are the server's to enforce.
          const tooLong: Address = {
            recipientName: 'A'.repeat(101),
            line1: 'Rua das Flores 12',
            city: 'Lisboa',
            postalCode: '1000-001',
            countryCode: 'PT',
            isDefault: false,
          };
          const failure = signedIn(mockServer.url).addOwnAddress(tooLong);
          await expect(failure).rejects.toBeInstanceOf(ProblemError);
          await failure.catch((error: unknown) => {
            expect((error as ProblemError).problem.errors[0]?.field).toBe('recipientName');
          });
        });
    });
  });
});
