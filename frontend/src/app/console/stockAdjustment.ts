import type { StockAdjustmentRequest } from '@app/catalog/catalogPort';
import { err, ok, type Result } from '@domain/result';

// `StockAdjustment` of data-model.md §3.4: what the operator types before it is sent. `delta` is
// a non-zero integer (positive adds, negative removes); `reason` is free text of 1..255
// characters, required, sent to the platform and never copied anywhere else (not into telemetry,
// not into storage, not into a URL). Whether the result would be negative is the platform's
// answer (422 next to `delta`), never computed here.
export const REASON_MAX = 255;

export type StockAdjustmentField = 'delta' | 'reason';
export type StockAdjustmentReasonError = 'required' | 'not-an-integer' | 'zero' | 'too-long';
export type StockAdjustmentFieldError = {
  readonly field: StockAdjustmentField;
  readonly reason: StockAdjustmentReasonError;
};

export type StockAdjustmentInput = { readonly delta: string; readonly reason: string };

const INTEGER = /^[+-]?\d+$/;

/** The delta as typed: an optionally signed whole number, not zero, within the safe integers. */
export function parseDelta(raw: string): Result<number, StockAdjustmentReasonError> {
  const text = raw.trim();
  if (text === '') return err('required');
  if (!INTEGER.test(text)) return err('not-an-integer');
  const delta = Number(text);
  if (!Number.isSafeInteger(delta)) return err('not-an-integer');
  return delta === 0 ? err('zero') : ok(delta);
}

/** The reason as it is sent: trimmed, 1..255 characters. */
export function reasonOf(raw: string): Result<string, StockAdjustmentReasonError> {
  const text = raw.trim();
  if (text === '') return err('required');
  return text.length > REASON_MAX ? err('too-long') : ok(text);
}

export function stockAdjustmentRequest(adjustment: {
  readonly delta: number;
  readonly reason: string;
}): StockAdjustmentRequest {
  return { delta: adjustment.delta, reason: adjustment.reason };
}

export const StockAdjustment = {
  /** Every invalid field is reported, delta first, so the form shows all of them at once. */
  parse(
    input: StockAdjustmentInput,
  ): Result<
    { readonly delta: number; readonly reason: string },
    readonly StockAdjustmentFieldError[]
  > {
    const delta = parseDelta(input.delta);
    const reason = reasonOf(input.reason);
    if (delta.ok && reason.ok) return ok({ delta: delta.value, reason: reason.value });
    return err([
      ...(delta.ok ? [] : [{ field: 'delta', reason: delta.reason } as const]),
      ...(reason.ok ? [] : [{ field: 'reason', reason: reason.reason } as const]),
    ]);
  },
} as const;
