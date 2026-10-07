import { defaultLocale } from '../components/Money.tsx';

/** An instant of the platform as a short local date and time; the raw text when it does not parse. */
export function formatInstant(instant: string, locale: string = defaultLocale()): string {
  const millis = Date.parse(instant);
  if (Number.isNaN(millis)) return instant;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(millis),
  );
}
