import type { components } from '@api/generated/identity';
import type { Listing } from '@app/catalog/browseParams';
import type { SignInSummary } from '@app/session/sessionStore';
import type { Address as AddressValue } from '@domain/address';
import type { Email } from '@domain/email';
import type { Password } from '@domain/password';

// The identity operations of the shopping journey and the account (register, verify, sign in and
// out, profile, addresses, notification preferences, password reset) over the
// generated identity contract; sign-in and refresh go through the gateway in cookie mode and
// answer the tokenless `{expiresAt, roles}`. Refusals are thrown as typed API errors, which the
// pages map to their states (contracts/storefront-routes.md).
export type Account = components['schemas']['Account'];
export type Address = components['schemas']['Address'];
export type AddressPage = components['schemas']['AddressPage'];
export type NotificationPreferences = components['schemas']['NotificationPreferences'];
export type NotificationChannel = components['schemas']['NotificationChannel'];

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
  /** Revokes the session of the signed-in account (204); the gateway deletes the cookie. */
  signOut(): Promise<void>;
  /** Changes the display name; a refusal (422 `errors[]`) is thrown. */
  updateOwnProfile(displayName: string): Promise<Account>;
  /** Anonymises the account (204); an operator account is refused (403, thrown). */
  deleteOwnAccount(): Promise<void>;
  /**
   * Proves the password of the signed-in account by signing in with it again: `true` when it is
   * right, `false` (without ending the session) when it is wrong; throttling and outages are thrown.
   */
  confirmPassword(email: string, password: Password): Promise<boolean>;
  /** Replaces a saved address; another account's address is 404 (thrown). */
  updateOwnAddress(id: string, address: AddressValue): Promise<Address>;
  /** Removes a saved address (204); another account's address is 404 (thrown). */
  deleteOwnAddress(id: string): Promise<void>;
  getOwnNotificationPreferences(): Promise<NotificationPreferences>;
  /** `sms` without a verified phone number is refused (422, thrown). */
  updateOwnNotificationPreferences(
    channels: readonly NotificationChannel[],
  ): Promise<NotificationPreferences>;
  /** Sends a one-time code by SMS (202). */
  requestPhoneVerification(phone: string): Promise<void>;
  /** Confirms the number with the code (204); a wrong code is refused (422, thrown). */
  confirmPhoneVerification(code: string): Promise<void>;
  /** Asks for a reset link; resolves with the generic message, identical for any email. */
  requestPasswordReset(email: Email): Promise<string>;
  /** Sets the new password with the emailed token (204); an invalid token is thrown (400/422). */
  completePasswordReset(token: string, newPassword: Password): Promise<void>;
};
