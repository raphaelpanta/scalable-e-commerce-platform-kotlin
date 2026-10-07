import createClient, { type Client, type Middleware } from 'openapi-fetch';

import { correlation as defaultCorrelation, type CorrelationSource } from '@app/correlation';

import {
  ApiError,
  problemFromResponse,
  ProblemError,
  retryAfterSeconds,
  ThrottledError,
  UnauthorizedError,
  UnavailableError,
} from './problem.ts';

// The one HTTP edge of the storefront. Every request carries `X-Browser-Session: cookie` (the
// gateway holds the credentials in an HttpOnly cookie, FR-014) and a canonical `X-Correlation-Id`;
// it is sent with `credentials: 'same-origin'` and never reads a token or a cookie. Non-2xx answers
// become typed errors (api/problem.ts) so callers never branch on raw status codes.
export const BROWSER_SESSION_HEADER = 'X-Browser-Session';
export const BROWSER_SESSION_MODE = 'cookie';
export const CORRELATION_HEADER = 'X-Correlation-Id';

export type ApiClientOptions = {
  readonly baseUrl?: string;
  readonly fetch?: (input: Request) => Promise<Response>;
  readonly correlation?: CorrelationSource;
};

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/** The session layer subscribes to learn about any 401 (data-model.md §3.1). */
export function subscribeUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

function notifyUnauthorized(): void {
  for (const listener of unauthorizedListeners) listener();
}

function browserSessionMiddleware(correlation: CorrelationSource): Middleware {
  return {
    onRequest({ request }) {
      request.headers.set(BROWSER_SESSION_HEADER, BROWSER_SESSION_MODE);
      request.headers.set(CORRELATION_HEADER, correlation.current().value);
      request.headers.delete('Authorization');
      return request;
    },
    async onResponse({ request, response }) {
      if (response.ok) return response;
      const correlationId =
        response.headers.get(CORRELATION_HEADER) ??
        request.headers.get(CORRELATION_HEADER) ??
        undefined;
      const problem = await problemFromResponse(response, correlationId);
      if (response.status === 429) {
        throw new ThrottledError(problem, retryAfterSeconds(response.headers.get('Retry-After')));
      }
      if (response.status === 401) {
        notifyUnauthorized();
        throw new UnauthorizedError(problem);
      }
      throw new ProblemError(problem);
    },
    onError({ request, error }) {
      if (error instanceof ApiError) return error;
      return new UnavailableError(request.headers.get(CORRELATION_HEADER) ?? undefined, error);
    },
  };
}

/** The typed body of a 2xx answer; a 2xx without a body where one is required is an outage. */
export function requireBody<T>(data: T | undefined, response: Response): T {
  if (data === undefined) {
    throw new UnavailableError(response.headers.get(CORRELATION_HEADER) ?? undefined, 'empty body');
  }
  return data;
}

/** True when `error` is a typed API error whose problem status is one of `statuses`. */
export function hasStatus(error: unknown, ...statuses: readonly number[]): boolean {
  return error instanceof ApiError && statuses.includes(error.problem.status);
}

/**
 * A typed client over the `paths` of one generated contract (src/api/generated, never
 * hand-written). Calls resolve with the typed `data` or reject with an `ApiError` subclass.
 */
export function createApiClient<Paths extends object>(
  options: ApiClientOptions = {},
): Client<Paths> {
  const client = createClient<Paths>({
    baseUrl: options.baseUrl ?? '',
    credentials: 'same-origin',
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });
  client.use(browserSessionMiddleware(options.correlation ?? defaultCorrelation));
  return client;
}
