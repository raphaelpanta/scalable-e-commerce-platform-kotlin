import { ApiError } from '@api/problem';

/**
 * The platform refusing the caller (403) behind a failed console query: its own explanation, so the
 * page can show the "not allowed" state without any data. `undefined` for every other failure.
 */
export function platformRefusal(
  error: unknown,
): { readonly detail: string | undefined } | undefined {
  if (!(error instanceof ApiError) || error.problem.status !== 403) return undefined;
  return { detail: error.problem.detail ?? error.problem.title };
}
