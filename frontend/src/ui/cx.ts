/** Joins class names, skipping the falsy ones (CSS Module lookups are `string | undefined`). */
export function cx(...parts: ReadonlyArray<string | undefined | null | false>): string {
  return parts.filter((part): part is string => typeof part === 'string' && part !== '').join(' ');
}
