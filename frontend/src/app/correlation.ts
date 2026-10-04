import { CorrelationId, type UuidSource } from '@domain/ids';

// One correlation id per page view, renewed per user action (data-model.md §2). The API client
// reads `current()` when it builds a request; UI action handlers call `next()` first.
export type CorrelationSource = {
  current(): CorrelationId;
  next(): CorrelationId;
};

function generate(source: UuidSource): CorrelationId {
  const result = CorrelationId.generate(source);
  if (!result.ok) throw new Error('the UUID source did not return a canonical UUID v4');
  return result.value;
}

export function createCorrelation(
  source: UuidSource = () => globalThis.crypto.randomUUID(),
): CorrelationSource {
  let current = generate(source);
  return {
    current: () => current,
    next: () => {
      current = generate(source);
      return current;
    },
  };
}

/** The application-wide source; a page view starts with a fresh id. */
export const correlation: CorrelationSource = createCorrelation();
