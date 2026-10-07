import { SpanStatusCode } from '@opentelemetry/api';
import type { ReadableSpan } from '@opentelemetry/sdk-trace';
import { describe, expect, it } from 'vitest';

import {
  ALLOWED_ATTRIBUTES,
  describeFailure,
  sanitizeAttributes,
  sanitizeLogRecord,
  sanitizeSpan,
} from '@telemetry/policy';

import {
  CORRELATION_ID,
  fakeLog,
  fakeSpan,
  ORIGIN,
  POLICY,
  serializeLogs,
  serializeSpans,
  SESSION_ID,
} from './support.ts';

// T092, example-based companions of policy.test.ts: the exact shape of what is exported, value by
// value, so that no accepted range, closed list or fixed field can drift unnoticed.
type Events = ReadableSpan['events'];

describe('the shape of what is exported', () => {
  it('accepts only values of the expected type', () => {
    const odd = [true, ['button'], { role: 'button' }, null, 5.5];
    for (const key of ALLOWED_ATTRIBUTES) {
      for (const value of odd) {
        expect(sanitizeAttributes({ [key]: value }, POLICY)).toStrictEqual({});
      }
    }
    expect(
      sanitizeAttributes(
        { 'http.url': 5, 'url.full': {}, 'error.type': 7, 'exception.type': [1], 'http.method': 3 },
        POLICY,
      ),
    ).toStrictEqual({});
    expect(sanitizeAttributes({ 'http.response.status_code': 4.5 }, POLICY)).toStrictEqual({});
    expect(sanitizeAttributes({ 'duration.ms': 1.5 }, POLICY)).toStrictEqual({});
  });

  it('keeps a status from 100 to 599 and a duration from 0, as numbers or digit strings', () => {
    const status = (value: unknown) =>
      sanitizeAttributes({ 'http.response.status_code': value }, POLICY);
    expect(status(100)).toStrictEqual({ 'http.response.status_code': 100 });
    expect(status(599)).toStrictEqual({ 'http.response.status_code': 599 });
    expect(status('404')).toStrictEqual({ 'http.response.status_code': 404 });
    for (const rejected of [99, 600, '99', '600', '404 ', ' 404', '4o4', '', '12345678901', -200]) {
      expect(status(rejected)).toStrictEqual({});
    }
    const duration = (value: unknown) => sanitizeAttributes({ 'duration.ms': value }, POLICY);
    expect(duration(0)).toStrictEqual({ 'duration.ms': 0 });
    expect(duration('15')).toStrictEqual({ 'duration.ms': 15 });
    expect(duration(-1)).toStrictEqual({});
    expect(duration('1234567890')).toStrictEqual({ 'duration.ms': 1_234_567_890 });
    expect(duration('12345678901')).toStrictEqual({});
    expect(duration('x1')).toStrictEqual({});
  });

  it('reads the error name from error.name, error.type or exception.type, in that order', () => {
    const name = (raw: Record<string, string>) => sanitizeAttributes(raw, POLICY)['error.name'];
    expect(name({ 'error.type': 'TypeError' })).toBe('TypeError');
    expect(name({ 'exception.type': 'RangeError' })).toBe('RangeError');
    expect(
      name({ 'error.name': 'Error', 'error.type': 'TypeError', 'exception.type': 'RangeError' }),
    ).toBe('Error');
    expect(name({ 'error.type': 'TypeError', 'exception.type': 'RangeError' })).toBe('TypeError');
    for (const rejected of [
      '',
      'Fail',
      'type error',
      'TypeError: boom',
      `${'E'.repeat(60)}Error`,
    ]) {
      expect(name({ 'error.name': rejected })).toBeUndefined();
    }
  });

  it('keeps the span context ids and flags, never the trace state, and marks the span ended', () => {
    const exported = sanitizeSpan(fakeSpan({ name: 'documentLoad' }), POLICY)!;
    expect(exported.ended).toBe(true);
    expect(exported.spanContext()).toStrictEqual({
      traceId: '5b8efff798038103d269b633813fc60c',
      spanId: 'eee19b7ec3c1b174',
      traceFlags: 1,
    });
    expect(exported.parentSpanContext).toStrictEqual({
      traceId: '5b8efff798038103d269b633813fc60c',
      spanId: '00f067aa0ba902b7',
      traceFlags: 1,
    });
    expect(exported.instrumentationScope).toStrictEqual({ name: 'storefront' });
    expect(exported.droppedAttributesCount).toBe(0);
    expect(exported.status).toStrictEqual({ code: SpanStatusCode.UNSET });
    const root = sanitizeSpan(fakeSpan({ name: 'documentLoad', parent: false }), POLICY)!;
    expect('parentSpanContext' in root).toBe(false);
    const { json } = serializeSpans([exported]);
    expect(json.resourceSpans?.[0]?.scopeSpans[0]?.scope.name).toBe('storefront');
  });

  it('names a platform call only when it has a valid method and a route', () => {
    const named = (attributes: Record<string, string>) =>
      sanitizeSpan(fakeSpan({ name: 'HTTP', attributes }), POLICY)?.name;
    expect(
      named({ 'http.request.method': 'DELETE', 'url.full': `${ORIGIN}/api/v1/cart/lines/x` }),
    ).toBe('DELETE /api/v1/cart/lines/:id');
    expect(named({ 'http.method': 'TRACE', 'http.url': `${ORIGIN}/api/v1/cart` })).toBeUndefined();
    expect(named({ 'http.method': 'GET' })).toBeUndefined();
  });

  it('ignores exception events that are not exceptions and tolerates ones without attributes', () => {
    const errorNameOf = (events: Events) =>
      sanitizeSpan(fakeSpan({ name: 'documentLoad', events }), POLICY)!.attributes['error.name'];
    const typed = (name: string, type: string) => ({
      name,
      time: [0, 0] as [number, number],
      attributes: { 'exception.type': type },
      droppedAttributesCount: 0,
    });
    expect(errorNameOf([typed('custom', 'TypeError')])).toBeUndefined();
    expect(
      errorNameOf([{ name: 'exception', time: [0, 0], droppedAttributesCount: 0 }]),
    ).toBeUndefined();
    expect(
      errorNameOf([
        { name: 'x', time: [0, 0], droppedAttributesCount: 0 },
        typed('exception', 'RangeError'),
      ]),
    ).toBe('RangeError');
  });

  it('exports a log record as a fixed ERROR client.error with its trace context and no trace state', () => {
    const spanContext = {
      traceId: '5b8efff798038103d269b633813fc60c',
      spanId: 'eee19b7ec3c1b174',
      traceFlags: 1,
    };
    const exported = sanitizeLogRecord(
      {
        ...fakeLog({ 'error.name': 'TypeError', 'correlation.id': CORRELATION_ID }),
        spanContext,
      },
      POLICY,
    );
    expect(exported.spanContext).toStrictEqual(spanContext);
    expect(exported.severityText).toBe('ERROR');
    expect(exported.severityNumber).toBe(17);
    expect(exported.body).toBe('client.error');
    expect(exported.instrumentationScope).toStrictEqual({ name: 'storefront' });
    expect(exported.droppedAttributesCount).toBe(0);
    expect(exported.attributes).toStrictEqual({
      'error.name': 'TypeError',
      'correlation.id': CORRELATION_ID,
    });
    const { json } = serializeLogs([exported]);
    expect(json.resourceLogs?.[0]?.scopeLogs[0]?.logRecords[0]?.severityText).toBe('ERROR');
    expect('spanContext' in sanitizeLogRecord(fakeLog(), POLICY)).toBe(false);
  });

  it('keeps the resource to the service, its version and the session id, each only when valid', () => {
    const resourceOf = (attributes: Record<string, string>) =>
      sanitizeSpan(fakeSpan({ name: 'documentLoad', resource: attributes }), POLICY)!.resource
        .attributes;
    expect(resourceOf({})).toStrictEqual({
      'service.name': 'storefront',
      'service.version': '0.1.0',
      'session.id': SESSION_ID,
    });
    expect(
      resourceOf({
        'service.name': 'other',
        'service.version': 'v1 beta',
        'session.id': 'abc',
      }),
    ).toStrictEqual({ 'service.name': 'storefront' });
    for (const version of ['0.1.0', '12.34.56', '1.0.0-rc.1']) {
      expect(resourceOf({ 'service.version': version })['service.version']).toBe(version);
    }
    for (const version of ['1.0', 'x.y.z', '1.0.0 ana@example.com', `1.0.0-${'a'.repeat(30)}`]) {
      expect(resourceOf({ 'service.version': version })['service.version']).toBeUndefined();
    }
  });

  it('reports a failure with a status only for a platform problem', () => {
    expect(describeFailure(Object.assign(new Error('x'), { problem: null }))).toStrictEqual({
      'error.name': 'Error',
    });
    expect(describeFailure(new TypeError('x'))).toStrictEqual({ 'error.name': 'TypeError' });
    expect(describeFailure(undefined)).toStrictEqual({ 'error.name': 'Error' });
    expect(
      describeFailure(Object.assign(new Error('x'), { problem: { status: 503 } })),
    ).toStrictEqual({ 'error.name': 'Error', 'http.response.status_code': 503 });
  });
  it('takes the route of a navigation from the page the interaction leads to', () => {
    const navigation = sanitizeSpan(
      fakeSpan({
        name: 'Navigation: /orders/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21?q=ana@example.com',
        attributes: { 'url.full': `${ORIGIN}/cart`, 'element.role': 'link' },
      }),
      POLICY,
    )!;
    expect(navigation.name).toBe('navigate');
    expect(navigation.attributes['http.route']).toBe('/orders/:id');
    expect(navigation.attributes['element.role']).toBe('link');
  });
});
