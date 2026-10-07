import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createCorrelation } from '@app/correlation';
import { isCanonicalUuidV4 } from '@domain/ids';
import type { DeliveryFetch } from '@telemetry/exporter';
import { initTelemetry, type Telemetry } from '@telemetry/setup';

import { asRecord, type LogJson, type SpanJson } from './support.ts';
import { server } from '../msw/server.ts';

// T093: the wiring of the web SDK. The exporters are real; only the network under them is a fake
// `fetch` that records what would leave the page.
type Sent = { url: string; body: string };

const ORIGIN = window.location.origin;
const SECRET_EMAIL = 'ana.secret@example.com';
const SECRET_TERM = 'private search words';

let telemetry: Telemetry | undefined;

function start(extra: { fetch?: DeliveryFetch } = {}) {
  const sent: Sent[] = [];
  const correlation = createCorrelation();
  const deliver: DeliveryFetch =
    extra.fetch ??
    ((url, init) => {
      sent.push({ url, body: init.body as string });
      return Promise.resolve(new Response('{}', { status: 200 }));
    });
  telemetry = initTelemetry({
    version: '0.1.0',
    correlation,
    storage: window.sessionStorage,
    fetch: () => deliver,
    batchDelayMillis: 60_000,
  });
  return { sent, correlation };
}

afterEach(async () => {
  await telemetry?.shutdown();
  telemetry = undefined;
  document.body.replaceChildren();
});

function spansOf(sent: Sent[]): SpanJson[] {
  return sent
    .filter((entry) => entry.url.endsWith('/traces'))
    .flatMap(
      (entry) =>
        (
          JSON.parse(entry.body) as {
            resourceSpans: Array<{ scopeSpans: Array<{ spans: SpanJson[] }> }>;
          }
        ).resourceSpans,
    )
    .flatMap((resource) => resource.scopeSpans.flatMap((scope) => scope.spans));
}

function logsOf(sent: Sent[]): LogJson[] {
  return sent
    .filter((entry) => entry.url.endsWith('/logs'))
    .flatMap(
      (entry) =>
        (
          JSON.parse(entry.body) as {
            resourceLogs: Array<{ scopeLogs: Array<{ logRecords: LogJson[] }> }>;
          }
        ).resourceLogs,
    )
    .flatMap((resource) => resource.scopeLogs.flatMap((scope) => scope.logRecords));
}

describe('failed actions and script errors become client.error records', () => {
  it('reports the class name, the route template and the correlation id, never the message', async () => {
    window.history.pushState(
      {},
      '',
      `/products/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21?q=${SECRET_TERM}`,
    );
    const { sent, correlation } = start();
    telemetry?.reportFailure(
      Object.assign(new TypeError(`cannot read ${SECRET_EMAIL}`), { name: 'TypeError' }),
    );
    await telemetry?.flush();
    const [record] = logsOf(sent);
    expect(record?.body?.stringValue).toBe('client.error');
    expect(asRecord(record?.attributes)).toEqual({
      'error.name': 'TypeError',
      'http.route': '/products/:id',
      'correlation.id': correlation.current().value,
    });
    const everything = sent.map((entry) => entry.body).join('');
    expect(everything).not.toContain(SECRET_EMAIL);
    expect(everything).not.toContain('0b4e6d1c');
    expect(everything).not.toContain('private');
    expect(everything).toContain('"storefront"');
    expect(everything).toContain('0.1.0');
    window.history.pushState({}, '', '/');
  });

  it('listens to window errors and unhandled rejections', async () => {
    const { sent } = start();
    window.dispatchEvent(new ErrorEvent('error', { error: new RangeError(SECRET_EMAIL) }));
    window.dispatchEvent(new ErrorEvent('error', { message: SECRET_TERM }));
    const rejection = new Event('unhandledrejection');
    Object.assign(rejection, { reason: new SyntaxError(SECRET_EMAIL) });
    window.dispatchEvent(rejection);
    await telemetry?.flush();
    expect(logsOf(sent).map((record) => asRecord(record.attributes)['error.name'])).toEqual([
      'RangeError',
      'Error',
      'SyntaxError',
    ]);
    expect(sent.map((entry) => entry.body).join('')).not.toContain(SECRET_EMAIL);
  });

  it('stops listening once shut down', async () => {
    start();
    const removed = vi.spyOn(window, 'removeEventListener');
    await telemetry?.shutdown();
    expect(removed.mock.calls.map(([type]) => type)).toEqual(
      expect.arrayContaining(['error', 'unhandledrejection', 'keydown']),
    );
    expect(removed.mock.calls.find(([type]) => type === 'keydown')?.[2]).toBe(true);
  });

  it('never throws into the page, whatever the network does', async () => {
    start({ fetch: () => Promise.reject(new TypeError('Failed to fetch')) });
    expect(() => {
      telemetry?.reportFailure(new Error('x'));
    }).not.toThrow();
    await expect(telemetry?.flush()).resolves.toBeUndefined();
  });

  it('keeps the session id in sessionStorage and never reads localStorage', async () => {
    const local = vi.spyOn(window, 'localStorage', 'get');
    const { sent } = start();
    telemetry?.reportFailure(new Error('x'));
    await telemetry?.flush();
    const stored = window.sessionStorage.getItem('storefront.telemetry.sessionId');
    expect(isCanonicalUuidV4(stored ?? '')).toBe(true);
    expect(sent[0]?.body).toContain(stored);
    expect(local).not.toHaveBeenCalled();
  });
});

describe('platform calls', () => {
  it('propagate traceparent, carry the correlation id of the request and export the route template', async () => {
    const { sent, correlation } = start();
    let traceparent: string | null = null;
    server.use(
      http.get(`${ORIGIN}/api/v1/catalog/products/:id`, ({ request }) => {
        traceparent = request.headers.get('traceparent');
        return HttpResponse.json({ ok: true });
      }),
    );
    const correlationId = correlation.next().value;
    await fetch(
      `${ORIGIN}/api/v1/catalog/products/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21?q=${SECRET_TERM}`,
      {
        headers: { 'X-Correlation-Id': correlationId },
      },
    );
    correlation.next();
    // the span of a fetch ends once the resource timing of the call is in (or after a short wait)
    await vi.waitFor(async () => {
      await telemetry?.flush();
      expect(spansOf(sent).some((candidate) => candidate.name.startsWith('GET'))).toBe(true);
    });
    expect(traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/);
    const [span] = spansOf(sent).filter((candidate) => candidate.name.startsWith('GET'));
    expect(span?.name).toBe('GET /api/v1/catalog/products/:id');
    expect(asRecord(span?.attributes)).toMatchObject({
      'http.route': '/api/v1/catalog/products/:id',
      'http.request.method': 'GET',
      'http.response.status_code': 200,
      'correlation.id': correlationId,
    });
    const everything = sent.map((entry) => entry.body).join('');
    expect(everything).not.toContain('0b4e6d1c');
    expect(everything).not.toContain('private');
  });

  it('never trace their own export', async () => {
    const { sent } = start();
    server.use(http.post(`${ORIGIN}/api/v1/telemetry/v1/logs`, () => HttpResponse.json({})));
    await fetch(`${ORIGIN}/api/v1/telemetry/v1/logs`, { method: 'POST', body: '{}' });
    await telemetry?.flush();
    expect(spansOf(sent)).toEqual([]);
  });
});

describe('interactions', () => {
  it('record a click as the role and the static id of the element, never its text', async () => {
    const { sent, correlation } = start();
    const button = document.createElement('button');
    button.id = 'add-to-cart';
    button.textContent = SECRET_EMAIL;
    const icon = document.createElement('span');
    icon.textContent = SECRET_TERM;
    button.append(icon);
    document.body.append(button);
    button.addEventListener('click', () => undefined);
    icon.click();
    await telemetry?.flush();
    const clicks = spansOf(sent).filter((span) => span.name === 'click');
    expect(clicks).toHaveLength(1);
    expect(asRecord(clicks[0]?.attributes)).toMatchObject({
      'element.role': 'button',
      'element.id': 'add-to-cart',
      'correlation.id': correlation.current().value,
      'http.route': '/',
    });
    expect(sent.map((entry) => entry.body).join('')).not.toContain(SECRET_EMAIL);
    expect(sent.map((entry) => entry.body).join('')).not.toContain('private');
  });

  it('record a submit as the role and id of the form', async () => {
    const { sent } = start();
    const form = document.createElement('form');
    form.id = 'sign-in-form';
    document.body.append(form);
    form.addEventListener('submit', (event) => {
      event.preventDefault();
    });
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await telemetry?.flush();
    const submits = spansOf(sent).filter((span) => span.name === 'submit');
    expect(submits).toHaveLength(1);
    expect(asRecord(submits[0]?.attributes)).toMatchObject({
      'element.role': 'form',
      'element.id': 'sign-in-form',
    });
  });

  it('record the Enter key as keypress-enter and no other key', async () => {
    const { sent } = start();
    const input = document.createElement('input');
    input.id = 'search';
    input.type = 'search';
    document.body.append(input);
    input.addEventListener('keydown', () => undefined);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    await telemetry?.flush();
    expect(spansOf(sent)).toEqual([]);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await telemetry?.flush();
    const [enter, ...others] = spansOf(sent);
    expect(others).toEqual([]);
    expect(enter?.name).toBe('keypress-enter');
    expect(asRecord(enter?.attributes)).toMatchObject({
      'element.role': 'searchbox',
      'element.id': 'search',
    });
  });

  it('keep the role of an element without an id and the id of one without a role', async () => {
    const { sent } = start();
    const button = document.createElement('button');
    const card = document.createElement('div');
    card.id = 'product-card';
    document.body.append(button, card);
    for (const element of [button, card]) element.addEventListener('click', () => undefined);
    button.click();
    card.click();
    await telemetry?.flush();
    const [first, second] = spansOf(sent).map((span) => asRecord(span.attributes));
    expect(first?.['element.role']).toBe('button');
    expect(first).not.toHaveProperty('element.id');
    expect(second?.['element.id']).toBe('product-card');
    expect(second).not.toHaveProperty('element.role');
  });

  it('record one span per user action, not one per listener or repeat within 50 ms', async () => {
    const { sent } = start();
    const first = document.createElement('button');
    const second = document.createElement('button');
    document.body.append(first, second);
    for (const element of [first, second]) {
      element.addEventListener('click', () => undefined);
      element.addEventListener('click', () => undefined, true);
    }
    let now = 1_000;
    vi.spyOn(performance, 'now').mockImplementation(() => now);
    first.click();
    now += 49;
    first.click();
    second.click();
    now += 50;
    first.click();
    await telemetry?.flush();
    expect(spansOf(sent).filter((span) => span.name === 'click')).toHaveLength(3);
  });

  it('stops instrumenting requests on shutdown', async () => {
    start();
    const seen: Array<string | null> = [];
    server.use(
      http.get(`${ORIGIN}/api/v1/cart`, ({ request }) => {
        seen.push(request.headers.get('traceparent'));
        return HttpResponse.json({});
      }),
    );
    await fetch(`${ORIGIN}/api/v1/cart`);
    await telemetry?.shutdown();
    await fetch(`${ORIGIN}/api/v1/cart`);
    expect(seen[0]).toMatch(/^00-/);
    expect(seen[1]).toBeNull();
  });

  it('ignore an element that is neither interactive nor has an id', async () => {
    const { sent } = start();
    const paragraph = document.createElement('p');
    paragraph.textContent = SECRET_TERM;
    document.body.append(paragraph);
    paragraph.addEventListener('click', () => undefined);
    paragraph.click();
    await telemetry?.flush();
    expect(spansOf(sent)).toEqual([]);
  });
});
