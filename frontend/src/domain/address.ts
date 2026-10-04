import { err, ok, type Result } from './result.ts';

// Mirrors identity's `AddressInput` bounds (data-model.md §2); trimmed; no per-country postal
// code rules (the server decides).
export type AddressInput = {
  readonly recipientName: string;
  readonly line1: string;
  readonly line2?: string | undefined;
  readonly city: string;
  readonly region?: string | undefined;
  readonly postalCode: string;
  readonly countryCode: string;
  readonly label?: string | undefined;
  readonly isDefault?: boolean | undefined;
};

export type Address = {
  readonly recipientName: string;
  readonly line1: string;
  readonly line2?: string;
  readonly city: string;
  readonly region?: string;
  readonly postalCode: string;
  readonly countryCode: string;
  readonly label?: string;
  readonly isDefault: boolean;
};

export type AddressRef = { readonly id: string };

export type AddressField = keyof Omit<Address, 'isDefault'>;
export type AddressFieldError = {
  readonly field: AddressField;
  readonly reason: 'required' | 'too-long' | 'invalid-format';
};

export const ADDRESS_BOUNDS = {
  recipientName: { min: 1, max: 100 },
  line1: { min: 1, max: 150 },
  line2: { min: 0, max: 150 },
  city: { min: 1, max: 100 },
  region: { min: 0, max: 100 },
  postalCode: { min: 1, max: 20 },
  label: { min: 0, max: 50 },
} as const;

const COUNTRY_CODE_PATTERN = /^[A-Z]{2}$/;

type BoundedField = keyof typeof ADDRESS_BOUNDS;

function checkBounded(
  field: BoundedField,
  raw: string | undefined,
  errors: AddressFieldError[],
): string | undefined {
  const value = (raw ?? '').trim();
  const bounds = ADDRESS_BOUNDS[field];
  if (value.length < bounds.min) {
    errors.push({ field, reason: 'required' });
    return undefined;
  }
  if (value.length > bounds.max) {
    errors.push({ field, reason: 'too-long' });
    return undefined;
  }
  return value.length === 0 ? undefined : value;
}

export const Address = {
  parse(input: AddressInput): Result<Address, readonly AddressFieldError[]> {
    const errors: AddressFieldError[] = [];
    const recipientName = checkBounded('recipientName', input.recipientName, errors);
    const line1 = checkBounded('line1', input.line1, errors);
    const line2 = checkBounded('line2', input.line2, errors);
    const city = checkBounded('city', input.city, errors);
    const region = checkBounded('region', input.region, errors);
    const postalCode = checkBounded('postalCode', input.postalCode, errors);
    const label = checkBounded('label', input.label, errors);
    const countryCode = input.countryCode.trim();
    if (countryCode.length === 0) errors.push({ field: 'countryCode', reason: 'required' });
    else if (!COUNTRY_CODE_PATTERN.test(countryCode)) {
      errors.push({ field: 'countryCode', reason: 'invalid-format' });
    }
    if (errors.length > 0 || recipientName === undefined || line1 === undefined) return err(errors);
    if (city === undefined || postalCode === undefined) return err(errors);
    return ok({
      recipientName,
      line1,
      ...(line2 === undefined ? {} : { line2 }),
      city,
      ...(region === undefined ? {} : { region }),
      postalCode,
      countryCode,
      ...(label === undefined ? {} : { label }),
      isDefault: input.isDefault ?? false,
    });
  },
} as const;
