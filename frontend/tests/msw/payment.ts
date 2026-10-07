import { http, HttpResponse } from 'msw';

import type { PaymentAttempt } from '@app/payment/paymentPort';

import { API } from './catalog.ts';

/** The payment attempts of the orders, as a test seeds them (`paymentServer.attempts`). */
export const ATTEMPTS_URL = `${API}/api/v1/payments/attempts`;

type PaymentServer = {
  attempts: PaymentAttempt[];
  reset(): void;
};

export const paymentServer: PaymentServer = {
  attempts: [],
  reset() {
    this.attempts = [];
  },
};

export const paymentHandlers = [
  http.get(ATTEMPTS_URL, ({ request }) => {
    const orderId = new URL(request.url).searchParams.get('orderId');
    const items = paymentServer.attempts.filter((attempt) => attempt.orderId === orderId);
    return HttpResponse.json({ items, page: 0, size: 20, totalItems: items.length });
  }),
];
