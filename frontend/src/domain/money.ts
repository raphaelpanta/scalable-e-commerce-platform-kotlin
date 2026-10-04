import { err, ok, type Result } from './result.ts';

// Minor units plus ISO 4217 code exactly as the platform sends them (data-model.md §2). The only
// operation is formatting; equality is allowed (price-change display); no arithmetic exists here
// because every total comes from the server.
export type MoneyError =
  'amount-not-integer' | 'amount-negative' | 'amount-unsafe' | 'invalid-currency';

export type Money = {
  readonly amountMinor: number;
  readonly currency: string;
};

const CURRENCY_PATTERN = /^[A-Z]{3}$/;

export const Money = {
  parse(input: { amountMinor: number; currency: string }): Result<Money, MoneyError> {
    const { amountMinor, currency } = input;
    if (!Number.isInteger(amountMinor)) return err('amount-not-integer');
    if (amountMinor < 0) return err('amount-negative');
    if (!Number.isSafeInteger(amountMinor)) return err('amount-unsafe');
    if (!CURRENCY_PATTERN.test(currency)) return err('invalid-currency');
    return ok({ amountMinor, currency });
  },
  equals(left: Money, right: Money): boolean {
    return left.amountMinor === right.amountMinor && left.currency === right.currency;
  },
  /** Minor-unit digits of the currency as `Intl` knows them (2 for unknown codes). */
  minorUnitDigits(currency: string): number {
    const options = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions();
    return options.maximumFractionDigits ?? 2;
  },
  format(money: Money, locale: string): string {
    const digits = Money.minorUnitDigits(money.currency);
    const major = money.amountMinor / 10 ** digits;
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: money.currency,
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(major);
  },
} as const;
