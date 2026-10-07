import { context } from '@opentelemetry/api';
import { ExportResultCode, type ExportResult, suppressTracing } from '@opentelemetry/core';
import { JsonLogsSerializer, JsonTraceSerializer } from '@opentelemetry/otlp-transformer';
import type { ReadableLogRecord } from '@opentelemetry/sdk-logs';
import type { ReadableSpan } from '@opentelemetry/sdk-trace';

import {
  type PauseGate,
  type PolicyContext,
  pauseMillis,
  sanitizeLogRecord,
  sanitizeSpan,
} from './policy.ts';

// The only exporter of the storefront (FR-032). It serialises OTLP/HTTP JSON itself instead of using
// the stock OTLP exporter classes because those retry up to five times with a back-off, and browser
// telemetry must be dropped, never retried (spec 005, FR-032). Every batch passes the policy first;
// a 429 pauses delivery for `Retry-After` and drops the batch; any other failure drops it; nothing
// is ever thrown into the page and the SDK always sees a success so it does not log or re-queue.
export const TRACES_PATH = '/api/v1/telemetry/v1/traces';
export const LOGS_PATH = '/api/v1/telemetry/v1/logs';
/** The gateway refuses more than 256 KiB (413); a larger batch is not sent at all. */
export const MAX_BODY_BYTES = 256 * 1024;
export const EXPORT_TIMEOUT_MS = 10_000;

export type DeliveryFetch = (input: string, init: RequestInit) => Promise<Response>;

export type DeliveryConfig = {
  readonly policy: PolicyContext;
  readonly gate: PauseGate;
  /** Resolved on every export so a patched `fetch` installed later is the one used. */
  readonly fetch: () => DeliveryFetch;
};

type Exporter<Item> = {
  export(items: Item[], resultCallback: (result: ExportResult) => void): void;
  shutdown(): Promise<void>;
  forceFlush(): Promise<void>;
};

const SUCCESS: ExportResult = { code: ExportResultCode.SUCCESS };

function byteLength(body: string): number {
  return new TextEncoder().encode(body).length;
}

async function post(url: string, body: string, config: DeliveryConfig): Promise<void> {
  if (config.gate.isPaused() || byteLength(body) > MAX_BODY_BYTES) return;
  try {
    // The handlers are attached synchronously (not through `await`) so that no zone ever sees the
    // rejection of an unreachable collector as unhandled.
    await config
      .fetch()(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
        // Anonymous by contract: no cookie, no credential travels with telemetry.
        credentials: 'omit',
        keepalive: true,
        signal: AbortSignal.timeout(EXPORT_TIMEOUT_MS),
      })
      .then(
        (response) => {
          if (response.status === 429) {
            config.gate.pauseFor(pauseMillis(response.headers.get('Retry-After')));
          }
        },
        () => undefined,
      );
  } catch {
    // Dropped silently: a fetch that throws, a collector that is down (503), an aborted request.
  }
}

function createExporter<Item>(
  url: string,
  config: DeliveryConfig,
  sanitize: (item: Item, policy: PolicyContext) => Item | undefined,
  serialize: (items: Item[]) => Uint8Array | undefined,
): Exporter<Item> {
  return {
    export(items, resultCallback) {
      try {
        const allowed = items.flatMap((item) => sanitize(item, config.policy) ?? []);
        const bytes = allowed.length === 0 ? undefined : serialize(allowed);
        if (bytes === undefined) {
          resultCallback(SUCCESS);
          return;
        }
        const body = new TextDecoder().decode(bytes);
        void context
          .with(suppressTracing(context.active()), () => post(url, body, config))
          .then(
            () => {
              resultCallback(SUCCESS);
            },
            () => {
              resultCallback(SUCCESS);
            },
          );
      } catch {
        resultCallback(SUCCESS);
      }
    },
    shutdown: () => Promise.resolve(),
    forceFlush: () => Promise.resolve(),
  };
}

export function createSpanExporter(config: DeliveryConfig): Exporter<ReadableSpan> {
  return createExporter<ReadableSpan>(TRACES_PATH, config, sanitizeSpan, (spans) =>
    JsonTraceSerializer.serializeRequest(spans),
  );
}

export function createLogExporter(config: DeliveryConfig): Exporter<ReadableLogRecord> {
  return createExporter<ReadableLogRecord>(LOGS_PATH, config, sanitizeLogRecord, (records) =>
    JsonLogsSerializer.serializeRequest(records),
  );
}
