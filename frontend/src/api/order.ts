import type {
  ChangedLine,
  DeclineReason,
  OrderPort,
  PlaceOrderResult,
  UnavailableLine,
} from '@app/order/orderPort';

import { type ApiClientOptions, createApiClient, hasStatus, requireBody } from './client.ts';
import type { components, paths as OrderPaths } from './generated/order';
import { type Problem, ProblemError, UnauthorizedError } from './problem.ts';

// Adapter of the order port over the generated order contract. `placeOrder` sends the mandatory
// `Idempotency-Key` and turns every documented refusal into a `PlaceOrderResult` from the
// problem `type` and its extension members (order.yaml 409 and 422), so the checkout state
// machine never inspects HTTP; throttling, outages and network failures stay thrown.
type Money = components['schemas']['Money'];

const DECLINE_REASONS: ReadonlySet<string> = new Set<DeclineReason>([
  'insufficient_funds',
  'card_expired',
  'card_rejected',
  'suspected_fraud',
  'invalid_payment_method',
]);

function isRecord(candidate: unknown): candidate is Record<string, unknown> {
  return typeof candidate === 'object' && candidate !== null;
}

function isMoney(candidate: unknown): candidate is Money {
  return (
    isRecord(candidate) &&
    typeof candidate['amountMinor'] === 'number' &&
    typeof candidate['currency'] === 'string'
  );
}

function isChangedLine(candidate: unknown): candidate is ChangedLine {
  return (
    isRecord(candidate) &&
    typeof candidate['lineId'] === 'string' &&
    typeof candidate['productId'] === 'string' &&
    isMoney(candidate['oldPrice']) &&
    isMoney(candidate['newPrice'])
  );
}

function isUnavailableLine(candidate: unknown): candidate is UnavailableLine {
  return (
    isRecord(candidate) &&
    typeof candidate['productId'] === 'string' &&
    typeof candidate['name'] === 'string' &&
    typeof candidate['requestedQuantity'] === 'number' &&
    typeof candidate['availableQuantity'] === 'number'
  );
}

function listOf<T>(candidate: unknown, guard: (item: unknown) => item is T): readonly T[] {
  return Array.isArray(candidate) ? candidate.filter(guard) : [];
}

function stringOf(candidate: unknown): string | undefined {
  return typeof candidate === 'string' && candidate.length > 0 ? candidate : undefined;
}

function declineReasonOf(candidate: unknown): DeclineReason | undefined {
  return typeof candidate === 'string' && DECLINE_REASONS.has(candidate)
    ? (candidate as DeclineReason)
    : undefined;
}

/** The checkout refusal a problem stands for; `undefined` for anything the caller must throw. */
export function refusalOf(problem: Problem): PlaceOrderResult | undefined {
  const members = problem.extensions ?? {};
  switch (problem.type) {
    case 'price-changed':
      return {
        kind: 'priceChanged',
        changedLines: listOf(members['changedLines'], isChangedLine),
        currentCartRevision: stringOf(members['currentCartRevision']) ?? '',
      };
    case 'insufficient-stock':
      return {
        kind: 'insufficientStock',
        unavailableLines: listOf(members['unavailableLines'], isUnavailableLine),
      };
    case 'order-cancelled':
      return {
        kind: 'orderCancelled',
        orderId: stringOf(members['orderId']) ?? '',
        cancellationReason: stringOf(members['cancellationReason']) ?? '',
      };
    case 'payment-declined':
      return {
        kind: 'paymentDeclined',
        declineReason: declineReasonOf(members['declineReason']),
        orderId: stringOf(members['orderId']),
      };
    case 'idempotency-key-reuse':
      return { kind: 'idempotencyConflict' };
    default:
      return problem.status >= 400 && problem.status < 500
        ? { kind: 'rejected', message: problem.detail ?? problem.title }
        : undefined;
  }
}

export function createOrderApi(options: ApiClientOptions = {}): OrderPort {
  const client = createApiClient<OrderPaths>(options);
  return {
    async placeOrder(request, key) {
      try {
        const { data, response } = await client.POST('/api/v1/orders', {
          params: { header: { 'Idempotency-Key': key.value } },
          body: request,
        });
        return { kind: 'placed', order: requireBody(data, response) };
      } catch (error) {
        if (error instanceof UnauthorizedError) return { kind: 'unauthorized' };
        if (error instanceof ProblemError) {
          const refusal = refusalOf(error.problem);
          if (refusal !== undefined) return refusal;
        }
        throw error;
      }
    },
    async getOwnOrder(id) {
      try {
        const { data, response } = await client.GET('/api/v1/orders/{orderId}', {
          params: { path: { orderId: id } },
        });
        return requireBody(data, response);
      } catch (error) {
        if (hasStatus(error, 404)) return null;
        throw error;
      }
    },
  };
}
