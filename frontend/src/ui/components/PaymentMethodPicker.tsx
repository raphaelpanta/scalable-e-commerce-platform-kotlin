import { type JSX, useId } from 'react';

import {
  PAYMENT_METHODS,
  PAYMENT_METHODS_SCOPE,
  type PaymentMethodId,
} from '@domain/paymentMethods';

import styles from './forms.module.css';

export type PaymentMethodPickerProps = {
  readonly selectedId: PaymentMethodId | undefined;
  readonly onSelect: (id: PaymentMethodId) => void;
};

/**
 * The payment methods the platform offers locally (FR-006): the seeded simulator outcomes as a
 * radio group with their labels. There is no card number field anywhere; the choice travels as an
 * opaque token.
 */
export function PaymentMethodPicker({
  selectedId,
  onSelect,
}: PaymentMethodPickerProps): JSX.Element {
  const name = useId();
  return (
    <fieldset className={styles.fieldset}>
      <legend className={styles.legend}>Payment method</legend>
      <p className={styles.hint}>{PAYMENT_METHODS_SCOPE}</p>
      <div className={styles.options}>
        {PAYMENT_METHODS.map((method) => {
          const id = `${name}-${method.id}`;
          return (
            <div key={method.id} className={styles.option}>
              <input
                id={id}
                className={styles.radio}
                type="radio"
                name={name}
                value={method.id}
                checked={selectedId === method.id}
                onChange={() => {
                  onSelect(method.id);
                }}
              />
              <label className={styles.optionText} htmlFor={id}>
                <span className={styles.label}>{method.label}</span>
                <span className={styles.hint}>{method.description}</span>
              </label>
            </div>
          );
        })}
      </div>
    </fieldset>
  );
}
