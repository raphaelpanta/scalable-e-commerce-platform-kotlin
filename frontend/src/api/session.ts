import type { IdentityPort } from '@app/identity/identityPort';
import type { ProbeResult, SessionPort } from '@app/session/sessionStore';

import { type ApiClientOptions, subscribeUnauthorized } from './client.ts';
import { createIdentityApi } from './identity.ts';
import { UnauthorizedError } from './problem.ts';

// Adapter of the session port: the page-load probe (`getOwnProfile`, 200 signed in, 401
// anonymous) and the cookie-mode sign-in come from the identity adapter; sign-out is the
// same `DELETE /sessions/current` the gateway handles as `browserSignOut` (it revokes the session
// and deletes the cookie).
export function createSessionPort(
  options: ApiClientOptions = {},
  identity: IdentityPort = createIdentityApi(options),
): SessionPort {
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
    signOut: () => identity.signOut(),
    subscribeUnauthorized,
  };
}
