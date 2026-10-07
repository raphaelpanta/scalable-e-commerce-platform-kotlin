import type {
  ConsoleOrdersParams,
  OrderConsolePort,
  TransitionResult,
} from '@app/console/consolePort';

import { type ApiClientOptions, createApiClient, hasStatus, requireBody } from './client.ts';
import type { paths as OrderPaths } from './generated/order';
import { ProblemError } from './problem.ts';

// Adapter of the operator console's order operations over the generated order contract:
// `listOwnOrders` as an operator (every shopper's orders, optional `orderStatus`) and
// `transitionOrderStatus`. A 409 (`invalid-transition`, including preparing without an approved
// payment) is a value carrying the platform's explanation; 403 and 404 are values too, so the
// console never branches on HTTP. 401, throttling and outages stay thrown.
type ListQuery = NonNullable<OrderPaths['/api/v1/orders']['get']['parameters']['query']>;

function listQuery(params: ConsoleOrdersParams): ListQuery {
  return {
    ...(params.page === undefined ? {} : { page: params.page }),
    ...(params.size === undefined ? {} : { size: params.size }),
    ...(params.orderStatus === undefined ? {} : { orderStatus: params.orderStatus }),
  };
}

function refusalOf(error: unknown): TransitionResult | undefined {
  if (hasStatus(error, 403)) return { kind: 'forbidden' };
  if (hasStatus(error, 404)) return { kind: 'notFound' };
  if (error instanceof ProblemError && hasStatus(error, 400, 409, 422)) {
    const { problem } = error;
    return { kind: 'refused', message: problem.detail ?? problem.title };
  }
  return undefined;
}

export function createOrderConsoleApi(options: ApiClientOptions = {}): OrderConsolePort {
  const client = createApiClient<OrderPaths>(options);
  return {
    async listOrders(params) {
      const { data, response } = await client.GET('/api/v1/orders', {
        params: { query: listQuery(params) },
      });
      return requireBody(data, response);
    },
    async transitionOrderStatus(orderId, target) {
      try {
        const { data, response } = await client.POST('/api/v1/orders/{orderId}/status', {
          params: { path: { orderId } },
          body: { orderStatus: target },
        });
        return { kind: 'transitioned', order: requireBody(data, response) };
      } catch (error) {
        const refusal = refusalOf(error);
        if (refusal !== undefined) return refusal;
        throw error;
      }
    },
  };
}
