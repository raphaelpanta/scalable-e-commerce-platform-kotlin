import type { Credentials } from './world.ts';

// The seeded operator acting through the public API (mirrors acceptance/.../support/Orders.kt):
// the order lifecycle is advanced by the platform's operator, never by the shopper's browser.
// One sign-in per process: the gateway's auth tier allows 10 sign-ins per minute per address.
let cachedToken: string | undefined;

async function bearer(baseUrl: string, operator: Credentials): Promise<string> {
  if (cachedToken !== undefined) return cachedToken;
  const response = await fetch(`${baseUrl}/api/v1/identity/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: operator.email, password: operator.password }),
  });
  if (response.status !== 200) {
    throw new Error(`operator sign-in failed with status ${String(response.status)}`);
  }
  const body = (await response.json()) as { accessToken?: unknown };
  if (typeof body.accessToken !== 'string') throw new Error('operator sign-in answered no token');
  cachedToken = body.accessToken;
  return cachedToken;
}

export type OperatorOrderStatus = 'preparing' | 'shipped' | 'delivered' | 'cancelled';

/** An operator moves an order to `status` (`transitionOrderStatus`). */
export async function transitionOrder(
  baseUrl: string,
  operator: Credentials,
  orderId: string,
  status: OperatorOrderStatus,
): Promise<void> {
  const token = await bearer(baseUrl.replace(/\/$/, ''), operator);
  const response = await fetch(`${baseUrl.replace(/\/$/, '')}/api/v1/orders/${orderId}/status`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ orderStatus: status }),
  });
  if (response.status !== 200) {
    throw new Error(
      `moving order ${orderId} to ${status} answered ${String(response.status)}, expected 200`,
    );
  }
}
