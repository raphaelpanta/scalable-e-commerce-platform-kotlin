// RFC 9457 problem details as every platform service and the gateway send them, mapped once at
// the API edge to typed errors the app and ui layers switch on (storefront-routes.md, FR-016).
export type FieldError = { readonly field: string; readonly message: string };

export type Problem = {
  /** The last segment of the problem `type` URI (`validation`, `unauthorized`, ...), `unknown` when absent. */
  readonly type: string;
  readonly title: string;
  readonly detail?: string;
  readonly status: number;
  readonly correlationId?: string;
  readonly errors: readonly FieldError[];
};

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(name: string, problem: Problem, message: string = problem.title) {
    super(message);
    this.name = name;
    this.problem = problem;
  }
}

/** Any 4xx/5xx other than 401 and 429. */
export class ProblemError extends ApiError {
  constructor(problem: Problem) {
    super('ProblemError', problem);
  }
}

/** 401: the session is gone; the session layer resets to anonymous. */
export class UnauthorizedError extends ApiError {
  constructor(problem: Problem) {
    super('UnauthorizedError', problem);
  }
}

/** 429 with `Retry-After`: no automatic retry, a countdown instead. */
export class ThrottledError extends ApiError {
  readonly retryAfterSeconds: number;

  constructor(problem: Problem, retryAfterSeconds: number) {
    super('ThrottledError', problem);
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Network failure or an unreadable answer: the platform could not be reached. */
export class UnavailableError extends ApiError {
  constructor(correlationId: string | undefined, cause: unknown) {
    super(
      'UnavailableError',
      {
        type: 'unavailable',
        title: 'The store is temporarily unavailable',
        status: 0,
        ...(correlationId === undefined ? {} : { correlationId }),
        errors: [],
      },
      'The store is temporarily unavailable',
    );
    this.cause = cause;
  }
}

export const DEFAULT_RETRY_AFTER_SECONDS = 30;

export function problemSlug(typeUri: string | undefined): string {
  if (typeUri === undefined || typeUri === '' || typeUri === 'about:blank') return 'unknown';
  const slug = typeUri
    .split('/')
    .filter((part) => part.length > 0)
    .at(-1);
  return slug ?? 'unknown';
}

function isFieldError(candidate: unknown): candidate is FieldError {
  if (typeof candidate !== 'object' || candidate === null) return false;
  const record = candidate as Record<string, unknown>;
  return typeof record['field'] === 'string' && typeof record['message'] === 'string';
}

function stringOrUndefined(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export function problemFromBody(
  body: unknown,
  status: number,
  correlationId: string | undefined,
): Problem {
  const record = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  const detail = stringOrUndefined(record['detail']);
  const rawErrors = record['errors'];
  return {
    type: problemSlug(stringOrUndefined(record['type'])),
    title: stringOrUndefined(record['title']) ?? `Request failed with status ${status}`,
    ...(detail === undefined ? {} : { detail }),
    status: typeof record['status'] === 'number' ? record['status'] : status,
    ...(correlationId === undefined ? {} : { correlationId }),
    errors: Array.isArray(rawErrors) ? rawErrors.filter(isFieldError) : [],
  };
}

export async function problemFromResponse(
  response: Response,
  correlationId: string | undefined,
): Promise<Problem> {
  const contentType = response.headers.get('content-type') ?? '';
  let body: unknown = undefined;
  if (contentType.includes('json')) {
    try {
      body = await response.json();
    } catch {
      body = undefined;
    }
  }
  return problemFromBody(body, response.status, correlationId);
}

/** `Retry-After` in whole seconds (delta-seconds or HTTP-date), never below 1. */
export function retryAfterSeconds(header: string | null, now: Date = new Date()): number {
  if (header === null || header.trim() === '') return DEFAULT_RETRY_AFTER_SECONDS;
  const seconds = Number(header);
  if (Number.isInteger(seconds)) return Math.max(1, seconds);
  const at = Date.parse(header);
  if (Number.isNaN(at)) return DEFAULT_RETRY_AFTER_SECONDS;
  return Math.max(1, Math.ceil((at - now.getTime()) / 1000));
}
