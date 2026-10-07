import { SpanStatusCode } from '@opentelemetry/api';
import * as fc from 'fast-check';
import { describe, expect, it } from 'vitest';

import { ROUTE_TEMPLATES, RouteTemplate } from '@domain/routeTemplate';
import {
  ALLOWED_ATTRIBUTES,
  ARIA_ROLES,
  describeFailure,
  HTTP_METHODS,
  sanitizeAttributes,
  sanitizeLogRecord,
  sanitizeSpan,
} from '@telemetry/policy';
import { API_ROUTE_TEMPLATES } from '@telemetry/routeTemplates';

import {
  accountId,
  asRecord,
  CORRELATION_ID,
  cookie,
  email,
  fakeLog,
  fakeSpan,
  fragments,
  freeText,
  jwt,
  ORIGIN,
  POLICY,
  queryString,
  searchTerm,
  sensitiveValue,
  serializeLogs,
  serializeSpans,
  SESSION_ID,
} from './support.ts';

// T092: the privacy policy of browser telemetry (FR-032, data-model.md §3.5). The properties below
// fuzz what an instrumentation or the app could put on a span or a log record and check what would
// leave the page after the policy: only the allow-listed attributes, only route templates, only
// class names, nothing typed by the shopper, no credential, no account id.
const EXPECTED_ALLOW_LIST = [
  'http.route',
  'http.request.method',
  'http.response.status_code',
  'element.role',
  'element.id',
  'duration.ms',
  'error.name',
  'correlation.id',
  'session.id',
];

const knownTemplates: readonly string[] = [...ROUTE_TEMPLATES, ...API_ROUTE_TEMPLATES, '/unknown'];

// Attribute keys of every kind an instrumentation, a browser or a developer might add.
const forbiddenKeys = [
  'http.url',
  'http.target',
  'url.full',
  'url.query',
  'url.path',
  'user.email',
  'user.id',
  'enduser.id',
  'http.request.header.cookie',
  'http.request.header.authorization',
  'http.response.header.set-cookie',
  'form.value',
  'search.term',
  'exception.message',
  'exception.stacktrace',
  'error.message',
  'target_xpath',
  'target_element',
  'event_type',
  'http.user_agent',
  'browser.user_agent',
  'account.id',
  'db.statement',
] as const;

const arbitraryKey = fc.oneof(fc.constantFrom(...forbiddenKeys), fc.string({ maxLength: 30 }));
const arbitraryValue = fc.oneof(
  sensitiveValue,
  fc.string({ maxLength: 40 }),
  fc.integer(),
  fc.double(),
  fc.boolean(),
  fc.array(fc.string({ maxLength: 10 }), { maxLength: 3 }),
);
const arbitraryAttributes = fc.dictionary(arbitraryKey, arbitraryValue, { maxKeys: 15 });

describe('the attribute allow-list', () => {
  it('is exactly route template, method, status, element role and id, duration, error name, correlation id and session id', () => {
    expect([...ALLOWED_ATTRIBUTES]).toEqual(EXPECTED_ALLOW_LIST);
  });

  it('is the only thing that survives, whatever keys and values arrive', () => {
    fc.assert(
      fc.property(arbitraryAttributes, (raw) => {
        const keys = Object.keys(sanitizeAttributes(raw, POLICY));
        expect(keys.filter((key) => !EXPECTED_ALLOW_LIST.includes(key))).toEqual([]);
      }),
    );
  });

  it('keeps every allow-listed attribute that has a valid value and nothing for an invalid one', () => {
    const attributes = sanitizeAttributes(
      {
        'http.route': '/products/:id',
        'http.request.method': 'get',
        'http.response.status_code': 404,
        'element.role': 'Button',
        'element.id': 'add-to-cart',
        'duration.ms': 120,
        'error.name': 'TypeError',
        'correlation.id': CORRELATION_ID,
        'session.id': SESSION_ID,
      },
      POLICY,
    );
    expect(attributes).toEqual({
      'http.route': '/products/:id',
      'http.request.method': 'GET',
      'http.response.status_code': 404,
      'element.role': 'button',
      'element.id': 'add-to-cart',
      'duration.ms': 120,
      'error.name': 'TypeError',
      'correlation.id': CORRELATION_ID,
      'session.id': SESSION_ID,
    });
    expect(
      sanitizeAttributes(
        {
          'http.request.method': 'TRACE',
          'http.response.status_code': 99,
          'element.role': 'x-custom',
          'element.id': 'two words',
          'duration.ms': -1,
          'error.name': 'not a class',
          'correlation.id': 'not-a-uuid',
          'session.id': 'not-a-uuid',
        },
        POLICY,
      ),
    ).toEqual({});
  });

  it('accepts the stable and the legacy names of method and status', () => {
    expect(
      sanitizeAttributes({ 'http.method': 'POST', 'http.status_code': '201' }, POLICY),
    ).toEqual({ 'http.request.method': 'POST', 'http.response.status_code': 201 });
  });

  it('keeps the closed vocabularies closed', () => {
    fc.assert(
      fc.property(arbitraryAttributes, (raw) => {
        const attributes = sanitizeAttributes(
          {
            ...raw,
            'http.request.method': raw['http.request.method'] ?? 'GET',
          },
          POLICY,
        );
        const method = attributes['http.request.method'];
        if (method !== undefined) expect(HTTP_METHODS).toContain(method);
        const role = attributes['element.role'];
        if (role !== undefined) expect(ARIA_ROLES).toContain(role);
        const status = attributes['http.response.status_code'];
        if (status !== undefined) {
          expect(Number.isInteger(status)).toBe(true);
          expect(status).toBeGreaterThanOrEqual(100);
          expect(status).toBeLessThanOrEqual(599);
        }
        const duration = attributes['duration.ms'];
        if (duration !== undefined) expect(Number.isInteger(duration)).toBe(true);
      }),
    );
  });
});

describe('what a span can carry out of the page', () => {
  const carriers = fc.record({
    name: sensitiveValue,
    sensitive: fc.array(sensitiveValue, { minLength: 1, maxLength: 4 }),
    query: queryString,
    segment: sensitiveValue,
    sensitiveEmail: email,
    sensitiveToken: jwt,
    sensitiveCookie: cookie,
    sensitiveText: freeText,
    sensitiveTerm: searchTerm,
    sensitiveAccount: accountId,
  });

  it('never contains a query string, form value, search term, free text, account id, email, token or cookie', () => {
    fc.assert(
      fc.property(carriers, fc.constantFrom(...ROUTE_TEMPLATES), (c, template) => {
        const path = template.replace(':id', encodeURIComponent(c.segment));
        const spans = [
          // a platform call whose URL, headers and error carry everything forbidden
          fakeSpan({
            name: `HTTP GET ${c.name}`,
            attributes: {
              'http.method': 'GET',
              'http.url': `${ORIGIN}/api/v1/catalog/products/${c.sensitiveAccount}${c.query}`,
              'http.target': `/api/v1/catalog/products?q=${c.sensitiveTerm}`,
              'http.request.header.authorization': `Bearer ${c.sensitiveToken}`,
              'http.request.header.cookie': c.sensitiveCookie,
              'http.status_code': 200,
              'user.email': c.sensitiveEmail,
              'form.value': c.sensitiveText,
              'search.term': c.sensitiveTerm,
              'correlation.id': CORRELATION_ID,
              'exception.message': c.sensitiveText,
              'error.name': c.sensitiveText,
              'element.id': c.sensitiveEmail,
              'element.role': c.sensitiveTerm,
            },
            events: [
              {
                name: 'exception',
                time: [1_790_000_000, 0],
                attributes: {
                  'exception.type': 'TypeError',
                  'exception.message': c.sensitiveText,
                  'exception.stacktrace': c.sensitiveToken,
                },
                droppedAttributesCount: 0,
              },
            ],
            status: { code: SpanStatusCode.ERROR, message: c.sensitiveText },
            traceState: `vendor=${c.sensitiveAccount}`,
          }),
          // a page view and an interaction on a storefront page whose path embeds an id
          fakeSpan({
            name: 'documentLoad',
            attributes: { 'http.url': `${ORIGIN}${path}${c.query}`, 'http.user_agent': c.name },
          }),
          fakeSpan({
            name: 'click',
            attributes: {
              'url.full': `${ORIGIN}${path}${c.query}`,
              target_xpath: `//*[@id="${c.sensitiveEmail}"]`,
              target_element: c.sensitiveText,
              'element.role': 'button',
              'element.id': 'add-to-cart',
            },
          }),
          fakeSpan({
            name: `Navigation: ${path}${c.query}`,
            attributes: { 'url.full': `${ORIGIN}${path}${c.query}` },
          }),
        ];
        const exported = spans.flatMap((span) => sanitizeSpan(span, POLICY) ?? []);
        expect(exported).toHaveLength(spans.length);
        const { text } = serializeSpans(exported);
        for (const value of [
          c.name,
          c.query,
          c.segment,
          c.sensitiveEmail,
          c.sensitiveToken,
          c.sensitiveCookie,
          c.sensitiveText,
          c.sensitiveTerm,
          c.sensitiveAccount,
          ...c.sensitive,
        ]) {
          for (const fragment of fragments(value)) expect(text).not.toContain(fragment);
        }
        expect(text).not.toContain('vendor=');
        expect(text).not.toContain('Mozilla');
      }),
    );
  });

  it('only exports allow-listed attributes on spans and only the three allowed resource keys', () => {
    fc.assert(
      fc.property(arbitraryAttributes, (raw) => {
        const exported = sanitizeSpan(fakeSpan({ name: 'documentLoad', attributes: raw }), POLICY);
        expect(exported).toBeDefined();
        const { json } = serializeSpans([exported!]);
        const resource = asRecord(json.resourceSpans?.[0]?.resource.attributes);
        expect(Object.keys(resource).sort()).toEqual([
          'service.name',
          'service.version',
          'session.id',
        ]);
        expect(resource).toEqual({
          'service.name': 'storefront',
          'service.version': '0.1.0',
          'session.id': SESSION_ID,
        });
        for (const span of json.resourceSpans?.[0]?.scopeSpans.flatMap((s) => s.spans) ?? []) {
          expect(
            Object.keys(asRecord(span.attributes)).filter(
              (key) => !EXPECTED_ALLOW_LIST.includes(key),
            ),
          ).toEqual([]);
        }
      }),
    );
  });

  it('drops events, links, the status message and the trace state', () => {
    const exported = sanitizeSpan(
      fakeSpan({
        name: 'documentLoad',
        events: [
          {
            name: 'exception',
            time: [0, 0],
            attributes: { 'exception.message': 'x' },
            droppedAttributesCount: 0,
          },
        ],
        status: { code: SpanStatusCode.ERROR, message: 'a message' },
      }),
      POLICY,
    )!;
    expect(exported.events).toEqual([]);
    expect(exported.links).toEqual([]);
    expect(exported.status).toEqual({ code: SpanStatusCode.ERROR });
    expect(exported.spanContext().traceState).toBeUndefined();
    expect(exported.parentSpanContext?.traceState).toBeUndefined();
    expect(exported.spanContext().traceId).toBe('5b8efff798038103d269b633813fc60c');
  });

  it('reports the duration in whole milliseconds and the class name of an exception', () => {
    const exported = sanitizeSpan(
      fakeSpan({
        name: 'HTTP POST',
        attributes: { 'http.method': 'POST', 'http.url': `${ORIGIN}/api/v1/orders` },
        events: [
          {
            name: 'exception',
            time: [0, 0],
            attributes: { 'exception.type': 'TypeError', 'exception.message': 'secret' },
            droppedAttributesCount: 0,
          },
        ],
      }),
      POLICY,
    )!;
    expect(exported.attributes).toEqual({
      'http.route': '/api/v1/orders',
      'http.request.method': 'POST',
      'duration.ms': 180,
      'error.name': 'TypeError',
    });
    expect(exported.name).toBe('POST /api/v1/orders');
  });

  it('names spans from a closed vocabulary and drops every other span', () => {
    const named = (name: string, attributes = {}): string | undefined =>
      sanitizeSpan(fakeSpan({ name, attributes }), POLICY)?.name;
    expect(named('documentLoad')).toBe('documentLoad');
    expect(named('documentFetch')).toBe('documentFetch');
    expect(named('click')).toBe('click');
    expect(named('submit')).toBe('submit');
    expect(named('keydown')).toBe('keypress-enter');
    expect(named('Navigation: /products/abc')).toBe('navigate');
    expect(named('HTTP GET', { 'http.method': 'GET', 'http.url': `${ORIGIN}/api/v1/cart` })).toBe(
      'GET /api/v1/cart',
    );
    expect(named('resourceFetch', { 'http.url': `${ORIGIN}/assets/index-abc.js` })).toBeUndefined();
    expect(named('HTTP GET', { 'http.url': `${ORIGIN}/api/v1/cart` })).toBeUndefined();
    fc.assert(
      fc.property(sensitiveValue, (name) => {
        expect(named(name)).toBeUndefined();
      }),
    );
  });
});

describe('what a log record can carry out of the page', () => {
  it('is a client.error event with allow-listed attributes, never the message or the app text', () => {
    fc.assert(
      fc.property(arbitraryAttributes, sensitiveValue, (raw, text) => {
        const exported = sanitizeLogRecord(
          fakeLog({ ...raw, 'exception.message': text }, text),
          POLICY,
        );
        const { text: serialized, json } = serializeLogs([exported]);
        const record = json.resourceLogs?.[0]?.scopeLogs[0]?.logRecords[0];
        expect(record?.body?.stringValue).toBe('client.error');
        expect(record?.eventName).toBeUndefined();
        expect(
          Object.keys(asRecord(record?.attributes)).filter(
            (key) => !EXPECTED_ALLOW_LIST.includes(key),
          ),
        ).toEqual([]);
        for (const fragment of fragments(text)) expect(serialized).not.toContain(fragment);
        expect(serialized).not.toContain('enduser');
        expect(serialized).not.toContain('example.com');
      }),
    );
  });
});

describe('URLs are reduced to route templates', () => {
  const dynamicSegment = sensitiveValue.map((value) => encodeURIComponent(value));

  it('turns any storefront URL into the RouteTemplate of its pathname', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...ROUTE_TEMPLATES),
        dynamicSegment,
        queryString,
        fc.constantFrom('http.url', 'url.full', 'http.target', 'http.route'),
        (template, segment, query, key) => {
          const pathname = template.replace(':id', segment);
          const value =
            key === 'http.target' ? `${pathname}${query}` : `${ORIGIN}${pathname}${query}`;
          const attributes = sanitizeAttributes({ [key]: value }, POLICY);
          expect(attributes['http.route']).toBe(RouteTemplate.fromPathname(pathname));
          expect(knownTemplates).toContain(attributes['http.route']);
        },
      ),
    );
  });

  it('turns any API URL into a template of the contracts and anything else into /unknown', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...API_ROUTE_TEMPLATES),
        dynamicSegment,
        queryString,
        (template, segment, query) => {
          const url = `${ORIGIN}${template.replaceAll(':id', segment)}${query}`;
          expect(sanitizeAttributes({ 'http.url': url }, POLICY)['http.route']).toBe(template);
        },
      ),
    );
    fc.assert(
      fc.property(fc.webUrl(), (url) => {
        expect(sanitizeAttributes({ 'http.url': url }, POLICY)['http.route']).toBe('/unknown');
      }),
    );
    expect(
      sanitizeAttributes(
        { 'http.url': `${ORIGIN}/api/v1/secret/0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21` },
        POLICY,
      ),
    ).toEqual({ 'http.route': '/unknown' });
  });
});

describe('the error name is a class name only', () => {
  it('reports the class of an Error and plain Error for anything else, never the message', () => {
    fc.assert(
      fc.property(sensitiveValue, (message) => {
        class ProblemError extends Error {
          constructor(text: string) {
            super(text);
            this.name = 'ProblemError';
          }
        }
        expect(describeFailure(new TypeError(message))).toEqual({ 'error.name': 'TypeError' });
        expect(describeFailure(new ProblemError(message))).toEqual({
          'error.name': 'ProblemError',
        });
        expect(describeFailure(message)).toEqual({ 'error.name': 'Error' });
        expect(describeFailure({ message })).toEqual({ 'error.name': 'Error' });
        const odd = new Error(message);
        odd.name = message;
        expect(describeFailure(odd)).toEqual({ 'error.name': 'Error' });
      }),
    );
  });

  it('adds the status of a platform problem and nothing else of it', () => {
    const problem = Object.assign(new Error('x'), {
      name: 'ThrottledError',
      problem: { status: 429, title: 'ana@example.com', detail: 'free text' },
    });
    expect(describeFailure(problem)).toEqual({
      'error.name': 'ThrottledError',
      'http.response.status_code': 429,
    });
    expect(describeFailure(Object.assign(new Error('x'), { problem: { status: 'nope' } }))).toEqual(
      {
        'error.name': 'Error',
      },
    );
    expect(describeFailure(Object.assign(new Error('x'), { problem: 'text' }))).toEqual({
      'error.name': 'Error',
    });
    expect(describeFailure(null)).toEqual({ 'error.name': 'Error' });
  });
});

describe('the element id is a static identifier', () => {
  it('accepts developer-assigned ids and rejects text, addresses, tokens and generated ids', () => {
    for (const id of ['add-to-cart', 'checkout_form', 'search', 'step-2', 'ProductCard']) {
      expect(sanitizeAttributes({ 'element.id': id }, POLICY)).toEqual({ 'element.id': id });
    }
    for (const id of [
      '',
      '1abc',
      'two words',
      'a@b',
      'a.b',
      'a'.repeat(64),
      'order-123456',
      'x-deadbeef',
    ]) {
      expect(sanitizeAttributes({ 'element.id': id }, POLICY)).toEqual({});
    }
    fc.assert(
      fc.property(sensitiveValue, (value) => {
        expect(sanitizeAttributes({ 'element.id': value }, POLICY)).toEqual({});
      }),
    );
  });
});
