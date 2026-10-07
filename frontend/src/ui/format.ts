// Dates and addresses as the shopper reads them, in the language of the page (falling back to the
// browser's). Dates are always rendered inside a `<time dateTime>` so the machine-readable instant
// stays exact.
function locale(): string {
  return document.documentElement.lang || navigator.language || 'en-US';
}

export function dateTimeText(instant: Date): string {
  if (Number.isNaN(instant.getTime())) return '';
  return new Intl.DateTimeFormat(locale(), { dateStyle: 'medium', timeStyle: 'short' }).format(
    instant,
  );
}

type DeliveryAddress = {
  readonly recipientName: string;
  readonly line1: string;
  readonly line2?: string | null | undefined;
  readonly city: string;
  readonly postalCode: string;
  readonly country: string;
};

function joinParts(parts: ReadonlyArray<string | null | undefined>): string {
  return parts.filter((part) => part !== undefined && part !== null && part !== '').join(', ');
}

/** The address of an order (the platform's frozen copy) as one line of text. */
export function deliveryAddressText(address: DeliveryAddress): string {
  return joinParts([
    address.recipientName,
    address.line1,
    address.line2,
    `${address.postalCode} ${address.city}`,
    address.country,
  ]);
}

type SavedAddress = {
  readonly recipientName: string;
  readonly line1: string;
  readonly line2?: string | undefined;
  readonly city: string;
  readonly region?: string | undefined;
  readonly postalCode: string;
  readonly countryCode: string;
};

/** A saved address of the account as one line of text. */
export function savedAddressText(address: SavedAddress): string {
  return joinParts([
    address.recipientName,
    address.line1,
    address.line2,
    `${address.postalCode} ${address.city}`,
    address.region,
    address.countryCode,
  ]);
}
