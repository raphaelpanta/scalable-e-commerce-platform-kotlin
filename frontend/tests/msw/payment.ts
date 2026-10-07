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
  http.get(`${ATTEMPTS_URL}/:attemptId`, ({ params }) => {
    const found = paymentServer.attempts.find((attempt) => attempt.id === params['attemptId']);
    if (found === undefined) {
      return HttpResponse.json(
        {
          type: 'https://ecommerce.example/problems/not-found',
          title: 'Not found',
          status: 404,
          detail: 'Payment attempt not found.',
        },
        { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
      );
    }
    return HttpResponse.json(found);
  }),
  http.get(ATTEMPTS_URL, ({ request }) => {
    const orderId = new URL(request.url).searchParams.get('orderId');
    const items = paymentServer.attempts.filter((attempt) => attempt.orderId === orderId);
    return HttpResponse.json({ items, page: 0, size: 20, totalItems: items.length });
  }),
];
