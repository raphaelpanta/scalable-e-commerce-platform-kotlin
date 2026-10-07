import type { Password } from '@domain/password';

import type { IdentityPort } from './identityPort.ts';
import type { SessionStore } from '../session/sessionStore.ts';

// Account deletion as the storefront performs it (FR-005, spec US4 scenario 5): the shopper proves
// the password first (identity's delete takes none, so the password is verified by signing in with
// it), the account is anonymised, and the session is ended. A wrong password deletes nothing.
export type DeleteAccountDependencies = {
  readonly identity: Pick<IdentityPort, 'confirmPassword' | 'deleteOwnAccount'>;
  readonly sessionStore: Pick<SessionStore, 'signOut'>;
};

export type DeleteAccountOutcome = 'deleted' | 'wrongPassword';

export async function deleteAccount(
  { identity, sessionStore }: DeleteAccountDependencies,
  email: string,
  password: Password,
): Promise<DeleteAccountOutcome> {
  if (!(await identity.confirmPassword(email, password))) return 'wrongPassword';
  await identity.deleteOwnAccount();
  try {
    await sessionStore.signOut();
  } catch {
    // The account's sessions were revoked with it: the sign-out may find none. The store resets
    // the summary and drops every cached query of the account either way.
  }
  return 'deleted';
}
