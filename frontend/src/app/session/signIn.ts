import type { Email } from '@domain/email';
import type { Password } from '@domain/password';

import type { SessionStore, SessionSummary } from './sessionStore.ts';
import type { CartActions } from '../cart/cartStore.ts';
import type { MergeNotice } from '../cart/cartView.ts';

// Sign-in as the storefront performs it (contracts/storefront-routes.md, data-model.md §3.1):
// the gateway sign-in first, then the merge of the anonymous cart into the account cart (the
// gateway supplies both credentials), whose capped lines become the merge notice. The merge never
// blocks the sign-in: a refusal (a merge in progress) or an outage leaves the cart to be re-read.
// Navigation to the validated `next` is the page's.
export type SignInOutcome = {
  readonly summary: SessionSummary;
  /** The capped lines of the merge; `null` when nothing was merged or the merge did not answer. */
  readonly mergeNotice: MergeNotice | null;
};

export type SignInDependencies = {
  readonly sessionStore: Pick<SessionStore, 'signIn'>;
  readonly cart: Pick<CartActions, 'merge'>;
};

export async function signInAndMerge(
  { sessionStore, cart }: SignInDependencies,
  email: Email,
  password: Password,
): Promise<SignInOutcome> {
  const summary = await sessionStore.signIn(email, password);
  let mergeNotice: MergeNotice | null = null;
  try {
    mergeNotice = await cart.merge();
  } catch {
    mergeNotice = null;
  }
  return { summary, mergeNotice };
}
