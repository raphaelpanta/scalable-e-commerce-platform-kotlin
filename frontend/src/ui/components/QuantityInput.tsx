import { type JSX, type SubmitEvent, useId, useState } from 'react';

import { Quantity, QUANTITY_MAX, QUANTITY_MIN } from '@domain/quantity';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import styles from './cart.module.css';

export type QuantityInputProps = {
  /** Names the field for assistive technology: "Quantity for Rake". */
  readonly productName: string;
  /** The quantity the platform reports; the field follows it when it changes. */
  readonly value: number;
  readonly busy?: boolean;
  readonly commitLabel?: string;
  readonly onCommit: (quantity: Quantity) => void;
};

/**
 * A whole number between 1 and 99 (the platform's per-line maximum), committed explicitly with
 * the button or Enter so a half-typed value is never sent. Invalid input is explained next to the
 * field; the value object decides what is valid.
 */
export function QuantityInput({
  productName,
  value,
  busy = false,
  commitLabel = 'Update',
  onCommit,
}: QuantityInputProps): JSX.Element {
  const inputId = useId();
  const errorId = useId();
  const [text, setText] = useState(String(value));
  const [error, setError] = useState<string | undefined>(undefined);
  // The field follows the platform's value when it changes (derived state, adjusted while rendering).
  const [shown, setShown] = useState(value);
  if (shown !== value) {
    setShown(value);
    setText(String(value));
    setError(undefined);
  }

  const onSubmit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const parsed = Quantity.parse(text.trim() === '' ? Number.NaN : Number(text));
    if (!parsed.ok) {
      setError(`Enter a whole number between ${QUANTITY_MIN} and ${QUANTITY_MAX}.`);
      return;
    }
    setError(undefined);
    onCommit(parsed.value);
  };

  return (
    <form className={styles.quantityForm} onSubmit={onSubmit} noValidate>
      <div className={styles.quantityField}>
        <label htmlFor={inputId}>Quantity for {productName}</label>
        <input
          id={inputId}
          className={styles.quantityInput}
          type="number"
          inputMode="numeric"
          min={QUANTITY_MIN}
          max={QUANTITY_MAX}
          step={1}
          value={text}
          disabled={busy}
          aria-invalid={error === undefined ? undefined : true}
          aria-describedby={error === undefined ? undefined : errorId}
          onChange={(event) => {
            setText(event.target.value);
          }}
        />
      </div>
      <button className={cx(buttons.button, buttons.secondary)} type="submit" disabled={busy}>
        {commitLabel}
      </button>
      {error === undefined ? null : (
        <p id={errorId} role="alert" className={styles.unavailable}>
          {error}
        </p>
      )}
    </form>
  );
}
