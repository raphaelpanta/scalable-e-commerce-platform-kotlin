import type { PaymentPort } from '@app/payment/paymentPort';

import { type ApiClientOptions, createApiClient, requireBody } from './client.ts';
import type { paths as PaymentPaths } from './generated/payment';

/** Adapter of the payment port over the generated payment contract (`listPaymentAttemptsForOrder`). */
export function createPaymentApi(options: ApiClientOptions = {}): PaymentPort {
  const client = createApiClient<PaymentPaths>(options);
  return {
    async listAttempts(orderId) {
      const { data, response } = await client.GET('/api/v1/payments/attempts', {
        params: { query: { orderId } },
      });
      return requireBody(data, response);
    },
  };
}
