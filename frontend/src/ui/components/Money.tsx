import type { JSX } from 'react';

import { Money as MoneyValue } from '@domain/money';

export type MoneyProps = {
  readonly value: MoneyValue;
  readonly locale?: string;
};

export function defaultLocale(): string {
  return document.documentElement.lang || navigator.language || 'en-US';
}

/** Formats an amount through the domain `Money.format`; never computes anything. */
export function Money({ value, locale }: MoneyProps): JSX.Element {
  return (
    <data value={String(value.amountMinor)}>
      {MoneyValue.format(value, locale ?? defaultLocale())}
    </data>
  );
}
