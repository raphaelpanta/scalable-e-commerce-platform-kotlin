import { type JSX, useId } from 'react';

import type { Address } from '@app/identity/identityPort';

import styles from './forms.module.css';

export type AddressPickerProps = {
  readonly addresses: readonly Address[];
  readonly selectedId: string | undefined;
  readonly onSelect: (addressId: string) => void;
};

function summary(address: Address): string {
  return [
    address.line1,
    address.line2,
    `${address.postalCode} ${address.city}`,
    address.region,
    address.countryCode,
  ]
    .filter((part): part is string => part !== undefined && part !== '')
    .join(', ');
}

/** The saved delivery addresses as a radio group; the chosen one is referenced by id only. */
export function AddressPicker({
  addresses,
  selectedId,
  onSelect,
}: AddressPickerProps): JSX.Element {
  const name = useId();
  return (
    <fieldset className={styles.fieldset}>
      <legend className={styles.legend}>Delivery address</legend>
      {addresses.length === 0 ? (
        <p className={styles.hint}>You have no saved address yet. Add one below.</p>
      ) : (
        <div className={styles.options}>
          {addresses.map((address) => {
            const id = `${name}-${address.id}`;
            const title = address.label ?? address.recipientName;
            return (
              <div key={address.id} className={styles.option}>
                <input
                  id={id}
                  className={styles.radio}
                  type="radio"
                  name={name}
                  value={address.id}
                  checked={selectedId === address.id}
                  onChange={() => {
                    onSelect(address.id);
                  }}
                />
                <label className={styles.optionText} htmlFor={id}>
                  <span className={styles.label}>{title}</span>
                  <span>{address.recipientName}</span>
                  <span className={styles.hint}>{summary(address)}</span>
                </label>
              </div>
            );
          })}
        </div>
      )}
    </fieldset>
  );
}
