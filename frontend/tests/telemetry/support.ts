import { type Attributes, SpanKind, SpanStatusCode } from '@opentelemetry/api';
import { SeverityNumber } from '@opentelemetry/api-logs';
import { TraceState } from '@opentelemetry/core';
import { JsonLogsSerializer, JsonTraceSerializer } from '@opentelemetry/otlp-transformer';
import { resourceFromAttributes } from '@opentelemetry/resources';
import type { ReadableLogRecord } from '@opentelemetry/sdk-logs';
import type { ReadableSpan } from '@opentelemetry/sdk-trace';
import * as fc from 'fast-check';

export const ORIGIN = 'http://localhost:8080';
export const POLICY = { origin: ORIGIN } as const;
export const SESSION_ID = '7b8e1a54-2f0c-4d6a-9a3e-5c1f0d2b8e77';
export const CORRELATION_ID = '3f1c9d52-8a14-4be0-b7f6-0e2d5a9c4c18';

const TRACE_ID = '5b8efff798038103d269b633813fc60c';
const SPAN_ID = 'eee19b7ec3c1b174';

type SpanParts = {
  name: string;
  attributes: Attributes;
  events: ReadableSpan['events'];
  status: ReadableSpan['status'];
  resource: Attributes;
  traceState: string;
  parent: boolean;
};

/** A finished span as the SDK would hand it to an exporter, with every part overridable. */
export function fakeSpan(parts: Partial<SpanParts> = {}): ReadableSpan {
  const {
    name = 'documentLoad',
    attributes = {},
    events = [],
    status = { code: SpanStatusCode.UNSET },
  } = parts;
  return {
    name,
    kind: SpanKind.CLIENT,
    spanContext: () => ({
      traceId: TRACE_ID,
      spanId: SPAN_ID,
      traceFlags: 1,
      traceState: new TraceState(parts.traceState ?? 'vendor=value'),
    }),
    ...(parts.parent === false
      ? {}
      : {
          parentSpanContext: {
            traceId: TRACE_ID,
            spanId: '00f067aa0ba902b7',
            traceFlags: 1,
            traceState: new TraceState('vendor=parent'),
          },
        }),
    startTime: [1_790_000_000, 0],
    endTime: [1_790_000_000, 180_000_000],
    status,
    attributes,
    links: [
      {
        context: { traceId: TRACE_ID, spanId: SPAN_ID, traceFlags: 1 },
        attributes: { 'link.note': 'ana@example.com' },
      },
    ],
    events,
    duration: [0, 180_000_000],
    ended: true,
    resource: resourceFromAttributes({
      'service.name': 'storefront',
      'service.version': '0.1.0',
      'session.id': SESSION_ID,
      'telemetry.sdk.language': 'webjs',
      'browser.user_agent': 'Mozilla/5.0 (secret)',
      ...parts.resource,
    }),
    instrumentationScope: { name: '@opentelemetry/instrumentation-fetch', version: '0.1' },
    droppedAttributesCount: 0,
    droppedEventsCount: 0,
    droppedLinksCount: 0,
  };
}

export function fakeLog(
  attributes: Attributes = {},
  body = 'boom ana@example.com',
): ReadableLogRecord {
  return {
    hrTime: [1_790_000_000, 0],
    hrTimeObserved: [1_790_000_000, 0],
    severityNumber: SeverityNumber.WARN,
    severityText: 'WARN',
    body,
    eventName: 'whatever.the.app.said',
    resource: resourceFromAttributes({
      'service.name': 'storefront',
      'service.version': '0.1.0',
      'session.id': SESSION_ID,
      'enduser.id': 'account-1',
    }),
    instrumentationScope: { name: 'app', attributes: { note: 'ana@example.com' } },
    attributes,
    droppedAttributesCount: 0,
  };
}

export type AnyValue = { stringValue?: string; intValue?: string | number; [key: string]: unknown };
type KeyValue = { key: string; value: AnyValue };
type Otlp = {
  resourceSpans?: Array<{
    resource: { attributes: KeyValue[] };
    scopeSpans: Array<{ scope: { name: string }; spans: SpanJson[] }>;
  }>;
  resourceLogs?: Array<{
    resource: { attributes: KeyValue[] };
    scopeLogs: Array<{ logRecords: LogJson[] }>;
  }>;
};
export type SpanJson = {
  name: string;
  attributes?: KeyValue[];
  events?: unknown[];
  links?: unknown[];
  status?: { message?: string; code?: number };
  traceState?: string;
  parentSpanId?: string;
};
export type LogJson = {
  body?: AnyValue;
  attributes?: KeyValue[];
  severityText?: string;
  eventName?: string;
};

export function serializeSpans(spans: ReadableSpan[]): { text: string; json: Otlp } {
  const bytes = JsonTraceSerializer.serializeRequest(spans);
  const text = new TextDecoder().decode(bytes);
  return { text, json: JSON.parse(text) as Otlp };
}

export function serializeLogs(records: ReadableLogRecord[]): { text: string; json: Otlp } {
  const bytes = JsonLogsSerializer.serializeRequest(records);
  const text = new TextDecoder().decode(bytes);
  return { text, json: JSON.parse(text) as Otlp };
}

/** `key -> string value` of an OTLP attribute list. */
export function asRecord(attributes: KeyValue[] | undefined): Record<string, string | number> {
  return Object.fromEntries(
    (attributes ?? []).map(({ key, value }) => [
      key,
      value.stringValue ?? Number(value['intValue'] ?? Number.NaN),
    ]),
  );
}

// Strings the platform must never see (FR-032). Each contains a character an identifier, a class
// name or a route template cannot contain, plus a random run of at least ten characters, so a
// substring check proves that no part of the value was copied into the export.
const run = fc.stringMatching(/^[a-z0-9]{10,20}$/);
const tainted = (separator: string): fc.Arbitrary<string> =>
  fc.tuple(run, run).map(([left, right]) => `${left}${separator}${right}`);

export const email = fc.tuple(run, run).map(([local, domain]) => `${local}@${domain}.example`);
export const jwt = fc
  .tuple(run, run, run)
  .map(([header, payload, signature]) => `eyJ${header}.${payload}.${signature}`);
export const cookie = run.map((value) => `session=k1.${value}; cart=${value}`);
export const freeText = fc.tuple(run, run, run).map((words) => words.join(' '));
export const searchTerm = tainted(' ');
export const queryString = run.map((value) => `?q=${value}&token=${value}`);
export const accountId = fc
  .uuid({ version: 4 })
  .filter((id) => id !== SESSION_ID && id !== CORRELATION_ID);

export const sensitiveValue: fc.Arbitrary<string> = fc.oneof(
  email,
  jwt,
  cookie,
  freeText,
  searchTerm,
  accountId,
  tainted('@'),
  tainted('.'),
  tainted('/'),
  tainted('='),
);

/** The pieces of a sensitive value: it must appear in the export neither whole nor in part. */
export function fragments(value: string): string[] {
  return [value, ...value.split(/[\s@./=;?&:]+/).filter((piece) => piece.length >= 10)];
}
