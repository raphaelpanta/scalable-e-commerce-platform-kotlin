import { type JSX, type SubmitEvent, useId, useState } from 'react';

import type { FieldError } from '@api/problem';
import {
  Address,
  type AddressField,
  type AddressFieldError,
  type AddressInput,
} from '@domain/address';

import { cx } from '../cx.ts';
import buttons from './buttons.module.css';
import styles from './forms.module.css';

export type AddressFormProps = {
  /** The accessible name of the form ("New delivery address" by default). */
  readonly label?: string;
  /** The values of the address being edited; absent for a new address. */
  readonly initial?: AddressInput;
  readonly busy?: boolean;
  /** Per-field refusals of the platform (422 `errors[]`), shown next to the fields. */
  readonly serverErrors?: readonly FieldError[];
  readonly onSubmit: (address: Address) => void;
  readonly onCancel?: () => void;
};

type TextField = Exclude<AddressField, 'countryCode'> | 'countryCode';

const FIELDS: ReadonlyArray<{
  readonly name: TextField;
  readonly label: string;
  readonly autoComplete: string;
  readonly required: boolean;
}> = [
  { name: 'recipientName', label: 'Recipient name', autoComplete: 'name', required: true },
  { name: 'line1', label: 'Address line 1', autoComplete: 'address-line1', required: true },
  { name: 'line2', label: 'Address line 2', autoComplete: 'address-line2', required: false },
  { name: 'city', label: 'City', autoComplete: 'address-level2', required: true },
  { name: 'region', label: 'Region', autoComplete: 'address-level1', required: false },
  { name: 'postalCode', label: 'Postal code', autoComplete: 'postal-code', required: true },
  { name: 'countryCode', label: 'Country code', autoComplete: 'country', required: true },
  { name: 'label', label: 'Label', autoComplete: 'off', required: false },
];

const EMPTY: Record<TextField, string> = {
  recipientName: '',
  line1: '',
  line2: '',
  city: '',
  region: '',
  postalCode: '',
  countryCode: '',
  label: '',
};

function valuesOf(initial: AddressInput | undefined): Record<TextField, string> {
  if (initial === undefined) return EMPTY;
  return {
    recipientName: initial.recipientName,
    line1: initial.line1,
    line2: initial.line2 ?? '',
    city: initial.city,
    region: initial.region ?? '',
    postalCode: initial.postalCode,
    countryCode: initial.countryCode,
    label: initial.label ?? '',
  };
}

function reasonText(error: AddressFieldError): string {
  switch (error.reason) {
    case 'required':
      return 'This field is required.';
    case 'too-long':
      return 'This value is too long.';
    case 'invalid-format':
      return 'Use the two-letter country code, for example BR or PT.';
  }
}

/**
 * A new delivery address (identity's `AddressInput` bounds, data-model.md §2). The value object
 * pre-checks presence and length; the platform's per-field errors are shown where they belong.
 * Nothing typed here is persisted by the storefront: the saved address is referenced by its id.
 */
export function AddressForm({
  label = 'New delivery address',
  initial,
  busy = false,
  serverErrors = [],
  onSubmit,
  onCancel,
}: AddressFormProps): JSX.Element {
  const prefix = useId();
  const [values, setValues] = useState(() => valuesOf(initial));
  const [isDefault, setIsDefault] = useState(initial?.isDefault ?? false);
  const [clientErrors, setClientErrors] = useState<readonly AddressFieldError[]>([]);

  const errorFor = (field: TextField): string | undefined => {
    const client = clientErrors.find((error) => error.field === field);
    if (client !== undefined) return reasonText(client);
    return serverErrors.find((error) => error.field === field)?.message;
  };

  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const input: AddressInput = {
      recipientName: values.recipientName,
      line1: values.line1,
      line2: values.line2 === '' ? undefined : values.line2,
      city: values.city,
      region: values.region === '' ? undefined : values.region,
      postalCode: values.postalCode,
      countryCode: values.countryCode.toUpperCase(),
      label: values.label === '' ? undefined : values.label,
      isDefault,
    };
    const parsed = Address.parse(input);
    if (!parsed.ok) {
      setClientErrors(parsed.reason);
      return;
    }
    setClientErrors([]);
    onSubmit(parsed.value);
  };

  return (
    <form className={styles.form} onSubmit={submit} aria-label={label}>
      {FIELDS.map((field) => {
        const id = `${prefix}-${field.name}`;
        const error = errorFor(field.name);
        return (
          <div key={field.name} className={styles.field}>
            <label className={styles.label} htmlFor={id}>
              {field.label}
              {field.required ? '' : ' (optional)'}
            </label>
            <input
              id={id}
              className={styles.input}
              type="text"
              autoComplete={field.autoComplete}
              value={values[field.name]}
              disabled={busy}
              aria-invalid={error === undefined ? undefined : true}
              aria-describedby={error === undefined ? undefined : `${id}-error`}
              onChange={(event) => {
                setValues({ ...values, [field.name]: event.target.value });
              }}
            />
            {error === undefined ? null : (
              <p id={`${id}-error`} className={styles.error} role="alert">
                {error}
              </p>
            )}
          </div>
        );
      })}
      <div className={styles.row}>
        <input
          id={`${prefix}-default`}
          type="checkbox"
          checked={isDefault}
          disabled={busy}
          onChange={(event) => {
            setIsDefault(event.target.checked);
          }}
        />
        <label htmlFor={`${prefix}-default`}>Use as my default address</label>
      </div>
      <div className={styles.row}>
        <button className={buttons.button} type="submit" disabled={busy}>
          Save address
        </button>
        {onCancel === undefined ? null : (
          <button
            className={cx(buttons.button, buttons.secondary)}
            type="button"
            disabled={busy}
            onClick={onCancel}
          >
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}
