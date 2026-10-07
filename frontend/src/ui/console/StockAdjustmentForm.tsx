import { type JSX, type SubmitEvent, useEffect, useId, useRef, useState } from 'react';

import type { FieldIssue } from '@app/catalog/catalogPort';
import {
  REASON_MAX,
  StockAdjustment,
  type StockAdjustmentFieldError,
  type StockAdjustmentReasonError,
  stockAdjustmentRequest,
} from '@app/console/stockAdjustment';
import { useAdjustStock } from '@app/console/useConsole';

import styles from './console.module.css';
import buttons from '../components/buttons.module.css';
import { describeError } from '../components/ErrorState.tsx';
import forms from '../components/forms.module.css';
import { cx } from '../cx.ts';

function deltaMessage(reason: StockAdjustmentReasonError): string {
  return reason === 'not-an-integer'
    ? 'Enter a whole number, for example 5 or -2.'
    : 'Enter a whole number other than zero.';
}

function reasonMessage(reason: StockAdjustmentReasonError): string {
  return reason === 'too-long' ? `Use at most ${String(REASON_MAX)} characters.` : 'Give a reason.';
}

type Feedback =
  | { readonly kind: 'adjusted'; readonly text: string }
  | { readonly kind: 'refused'; readonly text: string };

export type StockAdjustmentFormProps = {
  readonly productId: string;
  readonly productName: string;
  /** Units on hand as the list shows them (operator view), when known. */
  readonly available: number | undefined;
};

/**
 * One stock adjustment (data-model.md §3.4): a signed whole number of units and a required
 * reason. The reason goes to the platform and nowhere else: it is not stored, not put in the
 * address and not part of any telemetry. The platform's per-field answers (422) are shown next to
 * the field they name; the new quantity is the platform's, never computed here.
 */
export function StockAdjustmentForm({
  productId,
  productName,
  available,
}: StockAdjustmentFormProps): JSX.Element {
  const prefix = useId();
  const adjust = useAdjustStock();
  const deltaRef = useRef<HTMLInputElement>(null);
  const [delta, setDelta] = useState('');
  const [reason, setReason] = useState('');
  const [clientErrors, setClientErrors] = useState<readonly StockAdjustmentFieldError[]>([]);
  const [serverErrors, setServerErrors] = useState<readonly FieldIssue[]>([]);
  const [feedback, setFeedback] = useState<Feedback | undefined>(undefined);

  useEffect(() => {
    deltaRef.current?.focus();
  }, []);

  const deltaId = `${prefix}-delta`;
  const reasonId = `${prefix}-reason`;
  const messageFor = (
    field: 'delta' | 'reason',
    describe: (reason: StockAdjustmentReasonError) => string,
  ): string | undefined => {
    const client = clientErrors.find((error) => error.field === field);
    return client === undefined
      ? serverErrors.find((error) => error.field === field)?.message
      : describe(client.reason);
  };
  const deltaError = messageFor('delta', deltaMessage);
  const reasonError = messageFor('reason', reasonMessage);

  const submit = async (event: SubmitEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setFeedback(undefined);
    setServerErrors([]);
    const parsed = StockAdjustment.parse({ delta, reason });
    if (!parsed.ok) {
      setClientErrors(parsed.reason);
      return;
    }
    setClientErrors([]);
    try {
      const result = await adjust.mutateAsync({
        productId,
        request: stockAdjustmentRequest(parsed.value),
      });
      switch (result.kind) {
        case 'adjusted':
          setDelta('');
          setReason('');
          setFeedback({
            kind: 'adjusted',
            text:
              `Stock of ${productName} is now ${String(result.adjustment.newQuantity)} ` +
              `(was ${String(result.adjustment.previousQuantity)}).`,
          });
          return;
        case 'invalid': {
          const named = result.errors.filter(
            (error) => error.field === 'delta' || error.field === 'reason',
          );
          setServerErrors(named);
          if (named.length === 0) setFeedback({ kind: 'refused', text: result.message });
          return;
        }
        case 'notFound':
          setFeedback({ kind: 'refused', text: 'This product no longer exists.' });
          return;
        case 'forbidden':
          setFeedback({
            kind: 'refused',
            text: 'The platform refused this action: only operators can adjust stock.',
          });
          return;
      }
    } catch (error) {
      setFeedback({ kind: 'refused', text: describeError(error).message ?? 'Please try again.' });
    }
  };

  return (
    <form
      className={cx(forms.form, styles.panel)}
      aria-label={`Adjust stock of ${productName}`}
      noValidate
      onSubmit={(event) => {
        void submit(event);
      }}
    >
      <h2 className={styles.sectionTitle}>Adjust stock of {productName}</h2>
      {available === undefined ? null : <p>{available} units on hand.</p>}
      <div className={forms.field}>
        <label className={forms.label} htmlFor={deltaId}>
          Change in units
        </label>
        <input
          ref={deltaRef}
          id={deltaId}
          className={forms.input}
          type="text"
          inputMode="numeric"
          autoComplete="off"
          value={delta}
          disabled={adjust.isPending}
          aria-invalid={deltaError === undefined ? undefined : true}
          aria-describedby={deltaError === undefined ? `${deltaId}-hint` : `${deltaId}-error`}
          onChange={(event) => {
            setDelta(event.target.value);
          }}
        />
        {deltaError === undefined ? (
          <p id={`${deltaId}-hint`} className={forms.hint}>
            Positive adds stock, negative removes it.
          </p>
        ) : (
          <p id={`${deltaId}-error`} className={forms.error} role="alert">
            {deltaError}
          </p>
        )}
      </div>
      <div className={forms.field}>
        <label className={forms.label} htmlFor={reasonId}>
          Reason
        </label>
        <input
          id={reasonId}
          className={forms.input}
          type="text"
          autoComplete="off"
          value={reason}
          disabled={adjust.isPending}
          aria-invalid={reasonError === undefined ? undefined : true}
          aria-describedby={reasonError === undefined ? `${reasonId}-hint` : `${reasonId}-error`}
          onChange={(event) => {
            setReason(event.target.value);
          }}
        />
        {reasonError === undefined ? (
          <p id={`${reasonId}-hint`} className={forms.hint}>
            Required, up to {REASON_MAX} characters. It is kept in the stock history.
          </p>
        ) : (
          <p id={`${reasonId}-error`} className={forms.error} role="alert">
            {reasonError}
          </p>
        )}
      </div>
      <div className={forms.row}>
        <button className={buttons.button} type="submit" disabled={adjust.isPending}>
          Apply adjustment
        </button>
      </div>
      {feedback === undefined ? null : feedback.kind === 'adjusted' ? (
        <p role="status" className={styles.message}>
          {feedback.text}
        </p>
      ) : (
        <p role="alert" className={cx(styles.message, styles.refusal)}>
          {feedback.text}
        </p>
      )}
    </form>
  );
}
