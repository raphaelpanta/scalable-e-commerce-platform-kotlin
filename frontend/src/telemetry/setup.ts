import { context, propagation, type Span as ApiSpan, trace } from '@opentelemetry/api';
import { logs, SeverityNumber } from '@opentelemetry/api-logs';
import { ZoneContextManager } from '@opentelemetry/context-zone';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { UserInteractionInstrumentation } from '@opentelemetry/instrumentation-user-interaction';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { BatchLogRecordProcessor, LoggerProvider } from '@opentelemetry/sdk-logs';
import { BatchSpanProcessor, type Span, type SpanProcessor } from '@opentelemetry/sdk-trace';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';

import { type UuidSource } from '@domain/ids';
import { RouteTemplate } from '@domain/routeTemplate';

import { type ElementLike, interactiveTarget, roleOf } from './elements.ts';
import { createLogExporter, createSpanExporter, type DeliveryFetch } from './exporter.ts';
import {
  ATTRIBUTE_CORRELATION_ID,
  ATTRIBUTE_ELEMENT_ID,
  ATTRIBUTE_ELEMENT_ROLE,
  ATTRIBUTE_ROUTE,
  CLIENT_ERROR_EVENT,
  createPauseGate,
  describeFailure,
  SERVICE_NAME,
  type PauseGate,
} from './policy.ts';
import { sessionId, type SessionStorageLike } from './sessionId.ts';

// Wires the OpenTelemetry web SDK to the policy-wrapped exporters (research §4). Page load, fetch
// (with `traceparent` on same-origin requests), click/submit/Enter interactions and `error` log
// records leave the page only through `exporter.ts`, which applies the allow-list of `policy.ts`.
// Tests and the production build never reach this module (`main.tsx` calls it at runtime only).
export const CORRELATION_HEADER = 'X-Correlation-Id';
const TELEMETRY_URL_PATTERN = /\/api\/v1\/telemetry\//;
const BATCH_SIZE = 50;
const QUEUE_SIZE = 200;
const BATCH_DELAY_MS = 5_000;
// React listens to a click twice (capture and bubble) and every listener is instrumented: one user
// action is one span, so a repeat of the same event type on the same element this soon is ignored.
const REPEAT_WINDOW_MS = 50;

/** The correlation source of the application (`app/correlation`), as telemetry needs it. */
export type CorrelationReader = { current(): { readonly value: string } };

export type TelemetryOptions = {
  readonly version: string;
  readonly correlation: CorrelationReader;
  /** `window.sessionStorage`; `undefined` keeps the session id in memory for the page view. */
  readonly storage?: SessionStorageLike | undefined;
  readonly uuid?: UuidSource;
  readonly fetch?: () => DeliveryFetch;
  readonly gate?: PauseGate;
  readonly batchDelayMillis?: number;
};

export type Telemetry = {
  /** Reports a failed action or script error as a `client.error` record (class name only). */
  reportFailure(error: unknown): void;
  /** Exports what is queued now (page hide does it on its own). */
  flush(): Promise<void>;
  /** Stops the instrumentations and exporters; used by tests. */
  shutdown(): Promise<void>;
};

function currentRoute(): string {
  return RouteTemplate.fromPathname(globalThis.location.pathname);
}

/** Stamps every span with the correlation id current when it starts (renewed per user action). */
function correlationProcessor(correlation: CorrelationReader): SpanProcessor {
  return {
    onStart(span: Span) {
      span.setAttribute(ATTRIBUTE_CORRELATION_ID, correlation.current().value);
    },
    onEnd() {
      // Nothing to do: the policy runs at export.
    },
    forceFlush: () => Promise.resolve(),
    shutdown: () => Promise.resolve(),
  };
}

function headerOf(request: Request | RequestInit, name: string): string | undefined {
  try {
    return new Headers(request.headers).get(name) ?? undefined;
  } catch {
    return undefined;
  }
}

function describeElement(span: ApiSpan, element: ElementLike): boolean {
  const target = interactiveTarget(element);
  const role = roleOf(target);
  if (role === undefined && target.id === '') return false;
  if (role !== undefined) span.setAttribute(ATTRIBUTE_ELEMENT_ROLE, role);
  if (target.id !== '') span.setAttribute(ATTRIBUTE_ELEMENT_ID, target.id);
  return true;
}

function never(): void {
  // Telemetry failures are never surfaced to the page.
}

export function initTelemetry(options: TelemetryOptions): Telemetry {
  const origin = globalThis.location.origin;
  const session = sessionId(
    options.storage,
    options.uuid ?? (() => globalThis.crypto.randomUUID()),
  );
  const resource = resourceFromAttributes({
    'service.name': SERVICE_NAME,
    'service.version': options.version,
    'session.id': session,
  });
  const delivery = {
    policy: { origin },
    gate: options.gate ?? createPauseGate(),
    fetch: options.fetch ?? ((): DeliveryFetch => globalThis.fetch.bind(globalThis)),
  };
  const batch = { maxExportBatchSize: BATCH_SIZE, maxQueueSize: QUEUE_SIZE };
  const scheduledDelayMillis = options.batchDelayMillis ?? BATCH_DELAY_MS;

  const tracerProvider = new WebTracerProvider({
    resource,
    spanProcessors: [
      correlationProcessor(options.correlation),
      new BatchSpanProcessor({
        exporter: createSpanExporter(delivery),
        scheduledDelayMillis,
        ...batch,
      }),
    ],
  });
  tracerProvider.register({
    contextManager: new ZoneContextManager(),
    propagator: new W3CTraceContextPropagator(),
  });

  // The key of the keydown being dispatched: set at the window in the capture phase, so it is known
  // when the interaction instrumentation decides whether the keydown gets a span.
  let lastKey = '';
  let lastInteraction: { type: string; element: ElementLike; at: number } | undefined;
  const rememberKey = (event: KeyboardEvent): void => {
    lastKey = event.key;
  };
  globalThis.addEventListener('keydown', rememberKey, true);

  const unregisterInstrumentations = registerInstrumentations({
    tracerProvider,
    instrumentations: [
      new DocumentLoadInstrumentation(),
      new FetchInstrumentation({
        // Same-origin requests carry `traceparent` (cross-origin ones are not listed on purpose).
        ignoreUrls: [TELEMETRY_URL_PATTERN],
        applyCustomAttributesOnSpan(span, request) {
          const correlationId = headerOf(request, CORRELATION_HEADER);
          if (correlationId !== undefined)
            span.setAttribute(ATTRIBUTE_CORRELATION_ID, correlationId);
        },
      }),
      new UserInteractionInstrumentation({
        eventNames: ['click', 'submit', 'keydown'],
        shouldPreventSpanCreation(eventType, element, span) {
          if (eventType === 'keydown' && lastKey !== 'Enter') return true;
          const at = performance.now();
          const repeated =
            lastInteraction?.type === eventType &&
            lastInteraction.element === element &&
            at - lastInteraction.at < REPEAT_WINDOW_MS;
          lastInteraction = { type: eventType, element, at };
          return repeated || !describeElement(span, element);
        },
      }),
    ],
  });

  const loggerProvider = new LoggerProvider({
    resource,
    processors: [
      new BatchLogRecordProcessor({
        exporter: createLogExporter(delivery),
        scheduledDelayMillis,
        ...batch,
      }),
    ],
  });
  const logger = loggerProvider.getLogger(SERVICE_NAME);

  const reportFailure = (error: unknown): void => {
    try {
      logger.emit({
        severityNumber: SeverityNumber.ERROR,
        body: CLIENT_ERROR_EVENT,
        attributes: {
          ...describeFailure(error),
          [ATTRIBUTE_ROUTE]: currentRoute(),
          [ATTRIBUTE_CORRELATION_ID]: options.correlation.current().value,
        },
      });
    } catch {
      never();
    }
  };
  const onError = (event: ErrorEvent): void => {
    reportFailure(event.error);
  };
  const onRejection = (event: PromiseRejectionEvent): void => {
    reportFailure(event.reason);
  };
  globalThis.addEventListener('error', onError);
  globalThis.addEventListener('unhandledrejection', onRejection);

  return {
    reportFailure,
    flush: async () => {
      try {
        await Promise.all([tracerProvider.forceFlush(), loggerProvider.forceFlush()]);
      } catch {
        never();
      }
    },
    shutdown: async () => {
      globalThis.removeEventListener('keydown', rememberKey, true);
      globalThis.removeEventListener('error', onError);
      globalThis.removeEventListener('unhandledrejection', onRejection);
      unregisterInstrumentations();
      try {
        await Promise.all([tracerProvider.shutdown(), loggerProvider.shutdown()]);
      } catch {
        never();
      }
      trace.disable();
      context.disable();
      propagation.disable();
      logs.disable();
    },
  };
}
