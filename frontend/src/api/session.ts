import type { ProbeResult, SessionPort, SignInSummary } from '@app/session/sessionStore';
import type { Email } from '@domain/email';
import type { Password } from '@domain/password';

import { type ApiClientOptions, createApiClient, subscribeUnauthorized } from './client.ts';
import type { paths as GatewayPaths } from './generated/gateway-browser-session';
import type { paths as IdentityPaths } from './generated/identity';
import { UnauthorizedError, UnavailableError } from './problem.ts';

// Adapter of the session port over the gateway's browser-session contract (sign-in and sign-out
// in cookie mode) and identity's `getOwnProfile` (the page-load probe). The password is read
// exactly once, here, when the request body is built.
export function createSessionPort(options: ApiClientOptions = {}): SessionPort {
  const gateway = createApiClient<GatewayPaths>(options);
  const identity = createApiClient<IdentityPaths>(options);
  return {
    async probe(): Promise<ProbeResult> {
      try {
        const { data } = await identity.GET('/api/v1/identity/accounts/me');
        return { kind: 'signedIn', roles: data?.roles ?? [] };
      } catch (error) {
        if (error instanceof UnauthorizedError) return { kind: 'anonymous' };
        throw error;
      }
    },
    async signIn(email: Email, password: Password): Promise<SignInSummary> {
      const { data, response } = await gateway.POST('/api/v1/identity/sessions', {
        params: { header: { 'X-Browser-Session': 'cookie' } },
        body: { email: email.value, password: password.unwrap() },
      });
      if (data === undefined) {
        throw new UnavailableError(
          response.headers.get('X-Correlation-Id') ?? undefined,
          'empty body',
        );
      }
      return data;
    },
    async signOut(): Promise<void> {
      await gateway.DELETE('/api/v1/identity/sessions/current', {
        params: { header: { 'X-Browser-Session': 'cookie' } },
      });
    },
    subscribeUnauthorized,
  };
}
