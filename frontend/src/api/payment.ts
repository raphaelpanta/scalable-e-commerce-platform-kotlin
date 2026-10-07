import type { SimulatorRulesPort } from '@app/console/consolePort';
import type { PaymentPort } from '@app/payment/paymentPort';

import { type ApiClientOptions, createApiClient, hasStatus, requireBody } from './client.ts';
import type { paths as PaymentPaths } from './generated/payment';

/**
 * Adapter of the payment port over the generated payment contract (`listPaymentAttemptsForOrder`,
 * `getPaymentAttempt`).
 */
export function createPaymentApi(options: ApiClientOptions = {}): PaymentPort {
  const client = createApiClient<PaymentPaths>(options);
  return {
    async listAttempts(orderId) {
      const { data, response } = await client.GET('/api/v1/payments/attempts', {
        params: { query: { orderId } },
      });
      return requireBody(data, response);
    },
    async getAttempt(attemptId) {
      try {
        const { data, response } = await client.GET('/api/v1/payments/attempts/{attemptId}', {
          params: { path: { attemptId } },
        });
        return requireBody(data, response);
      } catch (error) {
        if (hasStatus(error, 404)) return null;
        throw error;
      }
    },
  };
}

/**
 * Adapter of the payment simulator's rule document (`getSimulatorRules`, operator only): the
 * console reads it; a shopper's 403 is a value, not a failure (pact-matrix.md P1).
 */
export function createPaymentRulesApi(options: ApiClientOptions = {}): SimulatorRulesPort {
  const client = createApiClient<PaymentPaths>(options);
  return {
    async getSimulatorRules() {
      try {
        const { data, response } = await client.GET('/api/v1/payments/simulator/rules');
        return { kind: 'rules', rules: requireBody(data, response) };
      } catch (error) {
        if (hasStatus(error, 403)) return { kind: 'forbidden' };
        throw error;
      }
    },
  };
}
