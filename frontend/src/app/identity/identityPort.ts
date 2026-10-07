import type { components } from '@api/generated/identity';
import type { Listing } from '@app/catalog/browseParams';
import type { SignInSummary } from '@app/session/sessionStore';
import type { Address as AddressValue } from '@domain/address';
import type { Email } from '@domain/email';
import type { Password } from '@domain/password';

// The identity operations of the shopping journey (register, verify, sign in, addresses) over the
// generated identity contract; sign-in and refresh go through the gateway in cookie mode and
// answer the tokenless `{expiresAt, roles}`. Refusals are thrown as typed API errors, which the
// pages map to their states (contracts/storefront-routes.md).
export type Account = components['schemas']['Account'];
export type Address = components['schemas']['Address'];
export type AddressPage = components['schemas']['AddressPage'];

export type IdentityPort = {
  /** Registers an account; resolves with the generic message, identical for a new and a known email. */
  register(email: Email, password: Password): Promise<string>;
  /** Verifies the emailed token (204); an invalid, used or expired token is thrown (400/422). */
  verifyEmail(token: string): Promise<void>;
  signIn(email: Email, password: Password): Promise<SignInSummary>;
  /** Silent renewal through the gateway (it supplies the refresh token from the cookie). */
  refresh(): Promise<SignInSummary>;
  getOwnProfile(): Promise<Account>;
  listOwnAddresses(params?: Listing): Promise<AddressPage>;
  /** Saves a new address (201); per-field refusals (422 `errors[]`) are thrown. */
  addOwnAddress(address: AddressValue): Promise<Address>;
};
