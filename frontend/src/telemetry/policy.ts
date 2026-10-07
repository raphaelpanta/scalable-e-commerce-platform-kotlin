import type { Attributes, AttributeValue, HrTime, SpanContext } from '@opentelemetry/api';
import { SeverityNumber } from '@opentelemetry/api-logs';
import { hrTimeToMilliseconds } from '@opentelemetry/core';
import { type Resource, resourceFromAttributes } from '@opentelemetry/resources';
import type { ReadableLogRecord } from '@opentelemetry/sdk-logs';
import type { ReadableSpan } from '@opentelemetry/sdk-trace';

import { isCanonicalUuidV4 } from '@domain/ids';

import { routeOf } from './routeTemplates.ts';

// The privacy policy of browser telemetry (FR-032, data-model.md §3.5, research §4). Everything the
// SDK or an instrumentation produced is rebuilt here from the allow-list before it reaches the
// exporter: a key that is not listed is dropped, a value that is not of the expected closed shape
// is dropped, URLs become route templates, span names come from a closed vocabulary, events, links,
// status messages and exception texts are never copied. Nothing typed by the shopper can pass.
export const SERVICE_NAME = 'storefront';

export const ATTRIBUTE_ROUTE = 'http.route';
export const ATTRIBUTE_METHOD = 'http.request.method';
export const ATTRIBUTE_STATUS = 'http.response.status_code';
export const ATTRIBUTE_ELEMENT_ROLE = 'element.role';
export const ATTRIBUTE_ELEMENT_ID = 'element.id';
export const ATTRIBUTE_DURATION = 'duration.ms';
export const ATTRIBUTE_ERROR_NAME = 'error.name';
export const ATTRIBUTE_CORRELATION_ID = 'correlation.id';
export const ATTRIBUTE_SESSION_ID = 'session.id';

/** The attributes a span or log record may carry (resource: service.name, service.version, session.id). */
export const ALLOWED_ATTRIBUTES = [
  ATTRIBUTE_ROUTE,
  ATTRIBUTE_METHOD,
  ATTRIBUTE_STATUS,
  ATTRIBUTE_ELEMENT_ROLE,
  ATTRIBUTE_ELEMENT_ID,
  ATTRIBUTE_DURATION,
  ATTRIBUTE_ERROR_NAME,
  ATTRIBUTE_CORRELATION_ID,
  ATTRIBUTE_SESSION_ID,
] as const;

export const CLIENT_ERROR_EVENT = 'client.error';

export const HTTP_METHODS: readonly string[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

// WAI-ARIA roles a storefront element can carry, explicitly or by its tag (`roleOf`).
export const ARIA_ROLES: readonly string[] = [
  'alert',
  'alertdialog',
  'article',
  'banner',
  'button',
  'cell',
  'checkbox',
  'combobox',
  'complementary',
  'contentinfo',
  'dialog',
  'figure',
  'form',
  'grid',
  'gridcell',
  'group',
  'heading',
  'img',
  'link',
  'list',
  'listbox',
  'listitem',
  'main',
  'menu',
  'menubar',
  'menuitem',
  'navigation',
  'none',
  'option',
  'presentation',
  'progressbar',
  'radio',
  'radiogroup',
  'region',
  'row',
  'rowgroup',
  'search',
  'searchbox',
  'separator',
  'spinbutton',
  'status',
  'switch',
  'tab',
  'table',
  'tablist',
  'tabpanel',
  'textbox',
  'toolbar',
  'tooltip',
];

// The developer-assigned id of an element: an identifier, never text (no space, no `@`, no `.`) and
// never a generated one (no uuid, no long hexadecimal or numeric run).
const ELEMENT_ID_PATTERN = /^(?![\w-]*(?:[0-9a-f]{8}|\d{4}))[A-Za-z][\w-]{0,62}$/i;
// A class name: `TypeError`, `ProblemError`, `DOMException`; never a message.
const ERROR_NAME_PATTERN = /^(?:[A-Z][A-Za-z0-9]{0,47})?(?:Error|Exception)$/;
const VERSION_PATTERN = /^\d{1,4}\.\d{1,4}\.\d{1,4}(?:-[A-Za-z0-9.]{1,20})?$/;
const MIN_STATUS = 100;
const MAX_STATUS = 599;

const SPAN_NAMES_KEPT: readonly string[] = ['documentLoad', 'documentFetch', 'click', 'submit'];
const NAVIGATION_PREFIX = 'Navigation:';
export const SPAN_NAME_ENTER = 'keypress-enter';
export const SPAN_NAME_NAVIGATE = 'navigate';

export type PolicyContext = { readonly origin: string };

// Whatever an instrumentation or the app put on a span or a record: nothing is trusted about it.
type RawAttributes = Readonly<Record<string, unknown>>;

function firstValue(raw: RawAttributes, keys: readonly string[]): unknown {
  for (const key of keys) {
    const value = raw[key];
    if (value !== undefined) return value;
  }
  return undefined;
}

function textOf(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function integerOf(value: unknown): number | undefined {
  if (typeof value === 'number') return Number.isInteger(value) ? value : undefined;
  if (typeof value === 'string' && /^\d{1,10}$/.test(value)) return Number(value);
  return undefined;
}

function matching(value: string | undefined, pattern: RegExp): string | undefined {
  return value !== undefined && pattern.test(value) ? value : undefined;
}

function uuidOf(value: unknown): string | undefined {
  const text = textOf(value);
  return text !== undefined && isCanonicalUuidV4(text) ? text : undefined;
}

function oneOf(value: string | undefined, allowed: readonly string[]): string | undefined {
  return value !== undefined && allowed.includes(value) ? value : undefined;
}

function statusOf(value: unknown): number | undefined {
  const status = integerOf(value);
  return status !== undefined && status >= MIN_STATUS && status <= MAX_STATUS ? status : undefined;
}

function durationOf(value: unknown): number | undefined {
  const duration = integerOf(value);
  return duration !== undefined && duration >= 0 ? duration : undefined;
}

/**
 * The allow-listed attributes of `raw`: the route template of whichever URL attribute is present
 * (`http.route`, `http.url`, `url.full`, `http.target`), the method and status under their stable
 * or legacy names, the element role and id, a duration, an error class name, the correlation id
 * and the session id. Every value is validated against its closed shape; anything else is dropped.
 */
export function sanitizeAttributes(raw: RawAttributes, context: PolicyContext): Attributes {
  const url = textOf(firstValue(raw, [ATTRIBUTE_ROUTE, 'http.url', 'url.full', 'http.target']));
  const method = textOf(firstValue(raw, [ATTRIBUTE_METHOD, 'http.method']))?.toUpperCase();
  const role = textOf(raw[ATTRIBUTE_ELEMENT_ROLE])?.toLowerCase();
  const candidates: Record<string, AttributeValue | undefined> = {
    [ATTRIBUTE_ROUTE]: url === undefined ? undefined : routeOf(url, context.origin),
    [ATTRIBUTE_METHOD]: oneOf(method, HTTP_METHODS),
    [ATTRIBUTE_STATUS]: statusOf(firstValue(raw, [ATTRIBUTE_STATUS, 'http.status_code'])),
    [ATTRIBUTE_ELEMENT_ROLE]: oneOf(role, ARIA_ROLES),
    [ATTRIBUTE_ELEMENT_ID]: matching(textOf(raw[ATTRIBUTE_ELEMENT_ID]), ELEMENT_ID_PATTERN),
    [ATTRIBUTE_DURATION]: durationOf(raw[ATTRIBUTE_DURATION]),
    [ATTRIBUTE_ERROR_NAME]: matching(
      textOf(firstValue(raw, [ATTRIBUTE_ERROR_NAME, 'error.type', 'exception.type'])),
      ERROR_NAME_PATTERN,
    ),
    [ATTRIBUTE_CORRELATION_ID]: uuidOf(raw[ATTRIBUTE_CORRELATION_ID]),
    [ATTRIBUTE_SESSION_ID]: uuidOf(raw[ATTRIBUTE_SESSION_ID]),
  };
  const result: Attributes = {};
  for (const key of ALLOWED_ATTRIBUTES) {
    const value = candidates[key];
    if (value !== undefined) result[key] = value;
  }
  return result;
}

/** The resource attributes that may leave the page: the service, its version and the session id. */
export function sanitizeResource(resource: Resource): Resource {
  const attributes: Attributes = { 'service.name': SERVICE_NAME };
  const version = matching(textOf(resource.attributes['service.version']), VERSION_PATTERN);
  if (version !== undefined) attributes['service.version'] = version;
  const session = uuidOf(resource.attributes[ATTRIBUTE_SESSION_ID]);
  if (session !== undefined) attributes[ATTRIBUTE_SESSION_ID] = session;
  return resourceFromAttributes(attributes);
}

function spanName(name: string, attributes: Attributes, isHttp: boolean): string | undefined {
  if (SPAN_NAMES_KEPT.includes(name)) return name;
  if (name === 'keydown') return SPAN_NAME_ENTER;
  if (name.startsWith(NAVIGATION_PREFIX)) return SPAN_NAME_NAVIGATE;
  const route = attributes[ATTRIBUTE_ROUTE];
  const method = attributes[ATTRIBUTE_METHOD];
  if (isHttp && typeof method === 'string' && typeof route === 'string') {
    return `${method} ${route}`;
  }
  return undefined;
}

function exceptionClass(span: ReadableSpan): string | undefined {
  const event = span.events.find((candidate) => candidate.name === 'exception');
  return textOf(event?.attributes?.['exception.type']);
}

function strippedContext(context: SpanContext): SpanContext {
  return { traceId: context.traceId, spanId: context.spanId, traceFlags: context.traceFlags };
}

function millisOf(duration: HrTime): number {
  return Math.max(0, Math.round(hrTimeToMilliseconds(duration)));
}

/**
 * The span as it may be exported, or `undefined` when it must not be exported at all: only the
 * page load, interaction, navigation and platform-call spans survive, under route-template names.
 */
export function sanitizeSpan(span: ReadableSpan, context: PolicyContext): ReadableSpan | undefined {
  // An interaction that navigates is renamed `Navigation: <url of the new page>` by the SDK.
  const destination = span.name.startsWith(NAVIGATION_PREFIX)
    ? { [ATTRIBUTE_ROUTE]: span.name.slice(NAVIGATION_PREFIX.length).trim() }
    : {};
  const raw: RawAttributes = {
    ...span.attributes,
    ...destination,
    [ATTRIBUTE_DURATION]: millisOf(span.duration),
    'exception.type': exceptionClass(span) ?? span.attributes['exception.type'],
  };
  const attributes = sanitizeAttributes(raw, context);
  const isHttp = firstValue(span.attributes, [ATTRIBUTE_METHOD, 'http.method']) !== undefined;
  const name = spanName(span.name, attributes, isHttp);
  if (name === undefined) return undefined;
  const parent = span.parentSpanContext;
  const spanContext = strippedContext(span.spanContext());
  return {
    name,
    kind: span.kind,
    spanContext: () => spanContext,
    ...(parent === undefined ? {} : { parentSpanContext: strippedContext(parent) }),
    startTime: span.startTime,
    endTime: span.endTime,
    // The status code only: the message of an error status is free text.
    status: { code: span.status.code },
    attributes,
    links: [],
    events: [],
    duration: span.duration,
    ended: true,
    resource: sanitizeResource(span.resource),
    instrumentationScope: { name: SERVICE_NAME },
    droppedAttributesCount: 0,
    droppedEventsCount: 0,
    droppedLinksCount: 0,
  };
}

/** The log record as it may be exported: a `client.error` event with allow-listed attributes. */
export function sanitizeLogRecord(
  record: ReadableLogRecord,
  context: PolicyContext,
): ReadableLogRecord {
  const spanContext =
    record.spanContext === undefined ? undefined : strippedContext(record.spanContext);
  return {
    hrTime: record.hrTime,
    hrTimeObserved: record.hrTimeObserved,
    ...(spanContext === undefined ? {} : { spanContext }),
    severityText: 'ERROR',
    severityNumber: SeverityNumber.ERROR,
    body: CLIENT_ERROR_EVENT,
    resource: sanitizeResource(record.resource),
    instrumentationScope: { name: SERVICE_NAME },
    attributes: sanitizeAttributes(record.attributes, context),
    droppedAttributesCount: 0,
  };
}

function problemStatusOf(error: object): number | undefined {
  const problem = (error as { problem?: unknown }).problem;
  if (typeof problem !== 'object' || problem === null) return undefined;
  return statusOf((problem as { status?: unknown }).status);
}

/**
 * The attributes of a failure report: the error's class name (`TypeError`, `ProblemError`; `Error`
 * for anything else) and, for a platform problem, its HTTP status. The message is never read.
 */
export function describeFailure(error: unknown): Attributes {
  const name = error instanceof Error ? matching(error.name, ERROR_NAME_PATTERN) : undefined;
  const status = typeof error === 'object' && error !== null ? problemStatusOf(error) : undefined;
  return {
    [ATTRIBUTE_ERROR_NAME]: name ?? 'Error',
    ...(status === undefined ? {} : { [ATTRIBUTE_STATUS]: status }),
  };
}

// Delivery rules (FR-032): a 429 pauses the exporter for `Retry-After` and drops the batch.
export const DEFAULT_PAUSE_MS = 60_000;
export const MIN_PAUSE_MS = 1_000;
export const MAX_PAUSE_MS = 3_600_000;

/** `Retry-After` in delta-seconds as milliseconds, bounded; any other form pauses for a minute. */
export function pauseMillis(retryAfter: string | null): number {
  const trimmed = retryAfter?.trim() ?? '';
  if (!/^\d{1,7}$/.test(trimmed)) return DEFAULT_PAUSE_MS;
  return Math.min(MAX_PAUSE_MS, Math.max(MIN_PAUSE_MS, Number(trimmed) * 1000));
}

export type PauseGate = {
  /** True while a 429 pause is running. */
  isPaused(): boolean;
  /** Pauses delivery for `millis` from now (never shortens a pause already running). */
  pauseFor(millis: number): void;
};

export function createPauseGate(now: () => number = () => Date.now()): PauseGate {
  let resumeAt = 0;
  return {
    isPaused: () => now() < resumeAt,
    pauseFor(millis) {
      resumeAt = Math.max(resumeAt, now() + millis);
    },
  };
}
