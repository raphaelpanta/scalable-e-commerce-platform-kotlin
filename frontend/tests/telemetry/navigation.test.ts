import { afterEach, describe, expect, it } from 'vitest';

import { createCorrelation } from '@app/correlation';
import { initTelemetry, type Telemetry } from '@telemetry/setup';

import { asRecord, type SpanJson } from './support.ts';

// T093: a click that navigates is exported as `navigate` with the route template of the page it
// leads to. The history patch of the interaction instrumentation outlives a shutdown, so this
// scenario owns its test file (one initialisation per page, as in the application).
const SECRET_TERM = 'private search words';

let telemetry: Telemetry | undefined;

afterEach(async () => {
  await telemetry?.shutdown();
  document.body.replaceChildren();
  window.history.pushState({}, '', '/');
});

describe('navigation', () => {
  it('is recorded as the route template of the new page, never its path or query', async () => {
    const bodies: string[] = [];
    telemetry = initTelemetry({
      version: '0.1.0',
      correlation: createCorrelation(),
      storage: window.sessionStorage,
      fetch: () => (_url, init) => {
        bodies.push(init.body as string);
        return Promise.resolve(new Response('{}', { status: 200 }));
      },
      batchDelayMillis: 60_000,
    });
    const link = document.createElement('a');
    link.href = '/orders';
    link.id = 'orders-link';
    document.body.append(link);
    link.addEventListener('click', (event) => {
      event.preventDefault();
      window.history.pushState(
        {},
        '',
        `/orders/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21?q=${SECRET_TERM}`,
      );
    });
    link.click();
    await telemetry.flush();
    const resources = bodies.flatMap(
      (body) =>
        (JSON.parse(body) as { resourceSpans: Array<{ scopeSpans: Array<{ spans: SpanJson[] }> }> })
          .resourceSpans,
    );
    const navigation = resources
      .flatMap((resource) => resource.scopeSpans.flatMap((scope) => scope.spans))
      .find((span) => span.name === 'navigate');
    expect(asRecord(navigation?.attributes)).toMatchObject({
      'http.route': '/orders/:id',
      'element.role': 'link',
      'element.id': 'orders-link',
    });
    expect(bodies.join('')).not.toContain('0b4e6d1c');
    expect(bodies.join('')).not.toContain('private');
  });
});
