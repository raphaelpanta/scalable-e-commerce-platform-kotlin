import type { Listing } from '@app/catalog/browseParams';
import type { IdentityPort } from '@app/identity/identityPort';
import type { Address } from '@domain/address';

import { type ApiClientOptions, createApiClient, requireBody } from './client.ts';
import type { paths as GatewayPaths } from './generated/gateway-browser-session';
import type { components, paths as IdentityPaths } from './generated/identity';
import { UnauthorizedError } from './problem.ts';

// Adapter of the identity port over the generated identity contract. Sign-in and refresh go
// through the gateway's browser-session contract (`X-Browser-Session: cookie`): the page receives
// `{expiresAt, roles}` and never a token. The password is read exactly once, here, when the body
// is built. Refusals (401, 403, 422 with `errors[]`, 429) are thrown as typed API errors.
type AddressInput = components['schemas']['AddressInput'];
type AddressQuery = NonNullable<
  IdentityPaths['/api/v1/identity/accounts/me/addresses']['get']['parameters']['query']
>;

function toAddressInput(address: Address): AddressInput {
  return {
    recipientName: address.recipientName,
    line1: address.line1,
    ...(address.line2 === undefined ? {} : { line2: address.line2 }),
    city: address.city,
    ...(address.region === undefined ? {} : { region: address.region }),
    postalCode: address.postalCode,
    countryCode: address.countryCode,
    ...(address.label === undefined ? {} : { label: address.label }),
    isDefault: address.isDefault,
  };
}

function addressQuery(params: Listing): AddressQuery {
  return {
    ...(params.page === undefined ? {} : { page: params.page }),
    ...(params.size === undefined ? {} : { size: params.size }),
  };
}

export function createIdentityApi(options: ApiClientOptions = {}): IdentityPort {
  const identity = createApiClient<IdentityPaths>(options);
  const gateway = createApiClient<GatewayPaths>(options);
  // The re-authentication answers 401 for a wrong password; that must not end the session.
  const reauthentication = createApiClient<GatewayPaths>({ ...options, reportUnauthorized: false });
  return {
    async register(email, password) {
      const { data, response } = await identity.POST('/api/v1/identity/accounts', {
        body: { email: email.value, password: password.unwrap() },
      });
      return requireBody(data, response).message;
    },
    async verifyEmail(token) {
      await identity.POST('/api/v1/identity/accounts/verify-email', { body: { token } });
    },
    async signIn(email, password) {
      const { data, response } = await gateway.POST('/api/v1/identity/sessions', {
        params: { header: { 'X-Browser-Session': 'cookie' } },
        body: { email: email.value, password: password.unwrap() },
      });
      return requireBody(data, response);
    },
    async refresh() {
      const { data, response } = await gateway.POST('/api/v1/identity/sessions/refresh', {
        params: { header: { 'X-Browser-Session': 'cookie' } },
      });
      return requireBody(data, response);
    },
    async getOwnProfile() {
      const { data, response } = await identity.GET('/api/v1/identity/accounts/me');
      return requireBody(data, response);
    },
    async listOwnAddresses(params = {}) {
      const { data, response } = await identity.GET('/api/v1/identity/accounts/me/addresses', {
        params: { query: addressQuery(params) },
      });
      return requireBody(data, response);
    },
    async addOwnAddress(address) {
      const { data, response } = await identity.POST('/api/v1/identity/accounts/me/addresses', {
        body: toAddressInput(address),
      });
      return requireBody(data, response);
    },
    async signOut() {
      await identity.DELETE('/api/v1/identity/sessions/current');
    },
    async updateOwnProfile(displayName) {
      const { data, response } = await identity.PUT('/api/v1/identity/accounts/me', {
        body: { displayName },
      });
      return requireBody(data, response);
    },
    async deleteOwnAccount() {
      await identity.DELETE('/api/v1/identity/accounts/me');
    },
    async confirmPassword(email, password) {
      try {
        await reauthentication.POST('/api/v1/identity/sessions', {
          params: { header: { 'X-Browser-Session': 'cookie' } },
          body: { email, password: password.unwrap() },
        });
        return true;
      } catch (error) {
        if (error instanceof UnauthorizedError) return false;
        throw error;
      }
    },
    async updateOwnAddress(id, address) {
      const { data, response } = await identity.PUT(
        '/api/v1/identity/accounts/me/addresses/{addressId}',
        { params: { path: { addressId: id } }, body: toAddressInput(address) },
      );
      return requireBody(data, response);
    },
    async deleteOwnAddress(id) {
      await identity.DELETE('/api/v1/identity/accounts/me/addresses/{addressId}', {
        params: { path: { addressId: id } },
      });
    },
    async getOwnNotificationPreferences() {
      const { data, response } = await identity.GET(
        '/api/v1/identity/accounts/me/notification-preferences',
      );
      return requireBody(data, response);
    },
    async updateOwnNotificationPreferences(channels) {
      const { data, response } = await identity.PUT(
        '/api/v1/identity/accounts/me/notification-preferences',
        { body: { channels: [...channels] } },
      );
      return requireBody(data, response);
    },
    async requestPhoneVerification(phone) {
      await identity.POST('/api/v1/identity/accounts/me/phone-verifications', {
        body: { phoneNumber: phone },
      });
    },
    async confirmPhoneVerification(code) {
      await identity.POST('/api/v1/identity/accounts/me/phone-verifications/confirm', {
        body: { code },
      });
    },
    async requestPasswordReset(email) {
      const { data, response } = await identity.POST('/api/v1/identity/password-resets', {
        body: { email: email.value },
      });
      return requireBody(data, response).message;
    },
    async completePasswordReset(token, newPassword) {
      await identity.POST('/api/v1/identity/password-resets/complete', {
        body: { token, newPassword: newPassword.unwrap() },
      });
    },
  };
}
