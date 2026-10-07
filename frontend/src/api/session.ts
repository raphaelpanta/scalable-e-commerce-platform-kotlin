import type { IdentityPort } from '@app/identity/identityPort';
import type { ProbeResult, SessionPort } from '@app/session/sessionStore';

import { type ApiClientOptions, createApiClient, subscribeUnauthorized } from './client.ts';
import type { paths as GatewayPaths } from './generated/gateway-browser-session';
import { createIdentityApi } from './identity.ts';
import { UnauthorizedError } from './problem.ts';

// Adapter of the session port: the page-load probe (`getOwnProfile`, 200 signed in, 401
// anonymous) and the cookie-mode sign-in come from the identity adapter; sign-out is the
// gateway's `browserSignOut`, which revokes the session and deletes the cookie.
export function createSessionPort(
  options: ApiClientOptions = {},
  identity: IdentityPort = createIdentityApi(options),
): SessionPort {
  const gateway = createApiClient<GatewayPaths>(options);
  return {
    async probe(): Promise<ProbeResult> {
      try {
        const account = await identity.getOwnProfile();
        return { kind: 'signedIn', roles: account.roles };
      } catch (error) {
        if (error instanceof UnauthorizedError) return { kind: 'anonymous' };
        throw error;
      }
    },
    signIn: (email, password) => identity.signIn(email, password),
    async signOut(): Promise<void> {
      await gateway.DELETE('/api/v1/identity/sessions/current', {
        params: { header: { 'X-Browser-Session': 'cookie' } },
      });
    },
    subscribeUnauthorized,
  };
}
