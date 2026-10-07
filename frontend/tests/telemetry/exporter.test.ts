import { ExportResultCode, type ExportResult } from '@opentelemetry/core';
import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import {
  createLogExporter,
  createSpanExporter,
  type DeliveryConfig,
  LOGS_PATH,
  MAX_BODY_BYTES,
  TRACES_PATH,
} from '@telemetry/exporter';
import {
  createPauseGate,
  DEFAULT_PAUSE_MS,
  MAX_PAUSE_MS,
  MIN_PAUSE_MS,
  pauseMillis,
} from '@telemetry/policy';

import { CORRELATION_ID, fakeLog, fakeSpan, POLICY } from './support.ts';

// T092 (delivery rules of FR-032): a 429 pauses the exporter for Retry-After and drops the batch; any
// failed export is dropped, never retried in a loop and never thrown into the page.
type Call = { url: string; init: RequestInit };

function harness(respond: (call: Call) => Promise<Response> | Response, start = 1_000_000) {
  const calls: Call[] = [];
  let clock = start;
  const gate = createPauseGate(() => clock);
  const config: DeliveryConfig = {
    policy: POLICY,
    gate,
    fetch: () => (url, init) => {
      const call = { url, init };
      calls.push(call);
      return Promise.resolve(respond(call));
    },
  };
  return {
    calls,
    config,
    gate,
    advance: (millis: number) => {
      clock += millis;
    },
  };
}

function exportOnce<T>(
  exporter: { export(items: T[], callback: (result: ExportResult) => void): void },
  items: T[],
): Promise<ExportResult> {
  return new Promise((resolve) => {
    exporter.export(items, resolve);
  });
}

const ok = (): Response => new Response('{}', { status: 200 });
const span = () =>
  fakeSpan({ name: 'documentLoad', attributes: { 'correlation.id': CORRELATION_ID } });

describe('the exporter delivers an OTLP/HTTP JSON batch to the same-origin telemetry route', () => {
  it('posts sanitised traces and logs anonymously', async () => {
    const h = harness(ok);
    expect(await exportOnce(createSpanExporter(h.config), [span()])).toEqual({
      code: ExportResultCode.SUCCESS,
    });
    expect(
      await exportOnce(createLogExporter(h.config), [fakeLog({ 'error.name': 'TypeError' })]),
    ).toEqual({
      code: ExportResultCode.SUCCESS,
    });
    expect(h.calls.map((call) => call.url)).toEqual([TRACES_PATH, LOGS_PATH]);
    expect(TRACES_PATH).toBe('/api/v1/telemetry/v1/traces');
    expect(LOGS_PATH).toBe('/api/v1/telemetry/v1/logs');
    for (const { init } of h.calls) {
      expect(init.method).toBe('POST');
      expect(init.credentials).toBe('omit');
      expect(init.headers).toEqual({ 'Content-Type': 'application/json' });
      expect(init.keepalive).toBe(true);
      expect(typeof init.body).toBe('string');
      expect(JSON.parse(init.body as string)).toBeTypeOf('object');
    }
    expect(h.calls[0]?.init.body).not.toContain('ana@example.com');
    expect(h.calls[1]?.init.body).toContain('client.error');
  });

  it('sends nothing when the policy leaves nothing to send', async () => {
    const h = harness(ok);
    const exporter = createSpanExporter(h.config);
    expect(await exportOnce(exporter, [fakeSpan({ name: 'resourceFetch' })])).toEqual({
      code: ExportResultCode.SUCCESS,
    });
    expect(await exportOnce(exporter, [])).toEqual({ code: ExportResultCode.SUCCESS });
    expect(h.calls).toEqual([]);
  });

  it('sends the spans the policy keeps and leaves out the ones it drops', async () => {
    const h = harness(ok);
    await exportOnce(createSpanExporter(h.config), [
      fakeSpan({ name: 'resourceFetch' }),
      span(),
      fakeSpan({ name: 'free text typed by ana@example.com' }),
    ]);
    expect(h.calls).toHaveLength(1);
    const body = JSON.parse(h.calls[0]?.init.body as string) as {
      resourceSpans: Array<{ scopeSpans: Array<{ spans: Array<{ name: string }> }> }>;
    };
    expect(body.resourceSpans[0]?.scopeSpans[0]?.spans.map((s) => s.name)).toEqual([
      'documentLoad',
    ]);
  });

  it('never sends a body the gateway would refuse (256 KiB)', async () => {
    const h = harness(ok);
    const exporter = createSpanExporter(h.config);
    const one = fakeSpan({ name: 'documentLoad' });
    const batch = Array.from({ length: 2_500 }, () => one);
    await exportOnce(exporter, batch);
    expect(h.calls).toEqual([]);
    expect(MAX_BODY_BYTES).toBe(262_144);
    await exportOnce(exporter, [one]);
    expect(h.calls).toHaveLength(1);
  });
});

describe('a 429 pauses the exporter for Retry-After seconds and drops the batch', () => {
  it('sends nothing during the pause, then resumes', async () => {
    const h = harness(() => new Response('{}', { status: 429, headers: { 'Retry-After': '30' } }));
    const exporter = createSpanExporter(h.config);
    const logs = createLogExporter(h.config);
    expect(await exportOnce(exporter, [span()])).toEqual({ code: ExportResultCode.SUCCESS });
    expect(h.calls).toHaveLength(1);
    expect(h.gate.isPaused()).toBe(true);

    h.advance(29_999);
    await exportOnce(exporter, [span()]);
    await exportOnce(logs, [fakeLog()]);
    expect(h.calls).toHaveLength(1);

    h.advance(1);
    expect(h.gate.isPaused()).toBe(false);
    await exportOnce(exporter, [span()]);
    expect(h.calls).toHaveLength(2);
  });

  it('pauses for a bounded time whatever the header says', () => {
    expect(pauseMillis('30')).toBe(30_000);
    expect(pauseMillis(' 7 ')).toBe(7_000);
    expect(pauseMillis('0')).toBe(MIN_PAUSE_MS);
    expect(pauseMillis('9999999')).toBe(MAX_PAUSE_MS);
    for (const header of [null, '', 'soon', '-5', '1.5', 'Wed, 21 Oct 2026 07:28:00 GMT', '1e3']) {
      expect(pauseMillis(header)).toBe(DEFAULT_PAUSE_MS);
    }
    fc.assert(
      fc.property(fc.string({ maxLength: 20 }), (header) => {
        const millis = pauseMillis(header);
        expect(millis).toBeGreaterThanOrEqual(MIN_PAUSE_MS);
        expect(millis).toBeLessThanOrEqual(MAX_PAUSE_MS);
      }),
    );
  });

  it('never shortens a pause already running', () => {
    let clock = 0;
    const gate = createPauseGate(() => clock);
    gate.pauseFor(10_000);
    gate.pauseFor(1_000);
    clock = 5_000;
    expect(gate.isPaused()).toBe(true);
    clock = 10_000;
    expect(gate.isPaused()).toBe(false);
    const real = createPauseGate();
    expect(real.isPaused()).toBe(false);
    real.pauseFor(60_000);
    expect(real.isPaused()).toBe(true);
  });
});

describe('a failed export is dropped', () => {
  const failures: Array<[string, () => Promise<Response> | Response]> = [
    ['503 unavailable', () => new Response('{}', { status: 503 })],
    ['413 payload-too-large', () => new Response('{}', { status: 413 })],
    ['500', () => new Response('{}', { status: 500 })],
    ['400', () => new Response('{}', { status: 400 })],
    ['a network failure', () => Promise.reject(new TypeError('Failed to fetch'))],
    [
      'a fetch that throws',
      () => {
        throw new Error('boom');
      },
    ],
  ];

  it.each(failures)(
    'on %s: one attempt, no retry, no throw, the SDK sees a success',
    async (_name, respond) => {
      const h = harness(respond);
      const exporter = createSpanExporter(h.config);
      const logs = createLogExporter(h.config);
      await expect(exportOnce(exporter, [span()])).resolves.toEqual({
        code: ExportResultCode.SUCCESS,
      });
      await expect(exportOnce(logs, [fakeLog()])).resolves.toEqual({
        code: ExportResultCode.SUCCESS,
      });
      expect(h.calls).toHaveLength(2);
      // a later batch is a new attempt, the failed one is gone
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(h.calls).toHaveLength(2);
      expect(h.gate.isPaused()).toBe(false);
    },
  );

  it('survives a fetch that cannot even be resolved', async () => {
    const config: DeliveryConfig = {
      policy: POLICY,
      gate: createPauseGate(),
      fetch: () => {
        throw new Error('no fetch');
      },
    };
    await expect(exportOnce(createSpanExporter(config), [span()])).resolves.toEqual({
      code: ExportResultCode.SUCCESS,
    });
  });

  it('survives an unserialisable batch', async () => {
    const h = harness(ok);
    const broken = {
      ...span(),
      spanContext: () => {
        throw new Error('bad span');
      },
    };
    await expect(exportOnce(createSpanExporter(h.config), [broken])).resolves.toEqual({
      code: ExportResultCode.SUCCESS,
    });
    expect(h.calls).toEqual([]);
  });

  it('has nothing to flush or stop', async () => {
    const exporter = createLogExporter(harness(ok).config);
    await expect(exporter.forceFlush()).resolves.toBeUndefined();
    await expect(exporter.shutdown()).resolves.toBeUndefined();
  });
});
