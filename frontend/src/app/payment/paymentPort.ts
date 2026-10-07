import type { components } from '@api/generated/payment';

// The payment attempts of an order (confirmation and order pages): outcome and decline category,
// newest first, over the generated payment contract.
export type PaymentAttempt = components['schemas']['PaymentAttempt'];
export type PaymentAttemptPage = components['schemas']['PaymentAttemptPage'];

export type PaymentPort = {
  listAttempts(orderId: string): Promise<PaymentAttemptPage>;
};
