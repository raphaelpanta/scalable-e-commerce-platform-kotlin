import type { CartPort } from '@app/cart/cartPort';

import { type ApiClientOptions, createApiClient, hasStatus, requireBody } from './client.ts';
import type { paths as CartPaths } from './generated/cart';
import type { paths as GatewayPaths } from './generated/gateway-browser-session';

// Adapter of the cart port over the generated cart contract, in cookie mode: the storefront sends
// no `X-Cart-Token` and no bearer (the gateway injects both from its cookies, FR-004), so the
// request types of the cart contract are used with their optional header left out, and the merge
// goes through the gateway's own `browserMergeCart` (the cart contract requires the token header
// the gateway supplies). A 404 on a read is "no cart"; every refusal of a write is thrown.
export function createCartApi(options: ApiClientOptions = {}): CartPort {
  const cart = createApiClient<CartPaths>(options);
  const gateway = createApiClient<GatewayPaths>(options);
  return {
    async getCart() {
      try {
        const { data, response } = await cart.GET('/api/v1/cart');
        return requireBody(data, response);
      } catch (error) {
        if (hasStatus(error, 404)) return null;
        throw error;
      }
    },
    async addLine(productId, quantity) {
      const { data, response } = await cart.POST('/api/v1/cart/lines', {
        body: { productId, quantity: quantity.value },
      });
      return requireBody(data, response);
    },
    async setLineQuantity(lineId, command) {
      const { data, response } = await cart.PUT('/api/v1/cart/lines/{lineId}', {
        params: { path: { lineId } },
        body: { quantity: command.value },
      });
      return requireBody(data, response);
    },
    async removeLine(lineId) {
      const { data, response } = await cart.DELETE('/api/v1/cart/lines/{lineId}', {
        params: { path: { lineId } },
      });
      return requireBody(data, response);
    },
    async clearCart() {
      await cart.DELETE('/api/v1/cart');
    },
    async mergeCart() {
      try {
        const { data, response } = await gateway.POST('/api/v1/cart/merge');
        return requireBody(data, response);
      } catch (error) {
        // 400: no anonymous cart to merge (no cart cookie); 404: its token was already consumed.
        if (hasStatus(error, 400, 404)) return null;
        throw error;
      }
    },
  };
}
