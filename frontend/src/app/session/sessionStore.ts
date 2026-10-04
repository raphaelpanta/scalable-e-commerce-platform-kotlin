import { type QueryClient, queryOptions } from '@tanstack/react-query';

import type { Email } from '@domain/email';
import type { Password } from '@domain/password';
import { type Role, ROLES } from '@domain/status';

// The tokenless session summary and its state machine (data-model.md §3.1). It holds no token,
// no password, no account id and no email; `expiresAt` is a hint for the "session about to end"
// warning and `roles` only gates console entry points. The truth is always the next response.
export type SessionSummary =
  | { readonly state: 'anonymous' }
  | {
      readonly state: 'signedIn';
      readonly expiresAt: Date | undefined;
      readonly roles: readonly Role[];
    };

/** Wire shape of the gateway's `SessionSummary` (`{expiresAt, roles}`). */
export type SignInSummary = { readonly expiresAt: string; readonly roles: readonly string[] };

/** Outcome of the page-load probe `GET /api/v1/identity/accounts/me`: 200 or 401. */
export type ProbeResult =
  { readonly kind: 'signedIn'; readonly roles: readonly string[] } | { readonly kind: 'anonymous' };

export type SessionEvent =
  | { readonly type: 'probed'; readonly result: ProbeResult }
  | { readonly type: 'signedIn'; readonly summary: SignInSummary }
  | { readonly type: 'renewed'; readonly expiresAt: string }
  | { readonly type: 'signedOut' }
  | { readonly type: 'unauthorized' }
  | { readonly type: 'idle' };

export const ANONYMOUS: SessionSummary = Object.freeze({ state: 'anonymous' } as const);
export const SESSION_QUERY_KEY = ['session'] as const;
export const SESSION_WARNING_LEAD_MS = 2 * 60 * 1000;

export function parseRoles(raw: readonly string[]): readonly Role[] {
  return raw.filter((role): role is Role => (ROLES as readonly string[]).includes(role));
}

function parseInstant(raw: string): Date | undefined {
  const millis = Date.parse(raw);
  return Number.isNaN(millis) ? undefined : new Date(millis);
}

export function reduceSession(current: SessionSummary, event: SessionEvent): SessionSummary {
  switch (event.type) {
    case 'probed':
      return event.result.kind === 'signedIn'
        ? { state: 'signedIn', expiresAt: undefined, roles: parseRoles(event.result.roles) }
        : ANONYMOUS;
    case 'signedIn':
      return {
        state: 'signedIn',
        expiresAt: parseInstant(event.summary.expiresAt),
        roles: parseRoles(event.summary.roles),
      };
    case 'renewed':
      return current.state === 'signedIn'
        ? { ...current, expiresAt: parseInstant(event.expiresAt) ?? current.expiresAt }
        : current;
    case 'signedOut':
    case 'unauthorized':
    case 'idle':
      return ANONYMOUS;
  }
}

export function hasRole(summary: SessionSummary, role: Role): boolean {
  return summary.state === 'signedIn' && summary.roles.includes(role);
}

export function warningAt(expiresAt: Date): Date {
  return new Date(expiresAt.getTime() - SESSION_WARNING_LEAD_MS);
}

/** True from `expiresAt − 2 min` onwards while signed in with a known expiry. */
export function sessionEndsSoon(summary: SessionSummary, now: Date): boolean {
  if (summary.state !== 'signedIn' || summary.expiresAt === undefined) return false;
  return now.getTime() >= warningAt(summary.expiresAt).getTime();
}

/** What the session use cases need from the API edge; implemented in src/api/session.ts. */
export type SessionPort = {
  probe(): Promise<ProbeResult>;
  signIn(email: Email, password: Password): Promise<SignInSummary>;
  signOut(): Promise<void>;
  subscribeUnauthorized(listener: () => void): () => void;
};

export type SessionStore = {
  readonly queryKey: typeof SESSION_QUERY_KEY;
  probeQuery(): ReturnType<typeof probeQueryOptions>;
  current(): SessionSummary;
  signIn(email: Email, password: Password): Promise<SessionSummary>;
  signOut(): Promise<void>;
  dispose(): void;
};

function probeQueryOptions(port: SessionPort) {
  return queryOptions({
    queryKey: SESSION_QUERY_KEY,
    queryFn: async (): Promise<SessionSummary> =>
      reduceSession(ANONYMOUS, { type: 'probed', result: await port.probe() }),
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
  });
}

/**
 * TanStack Query based store: the summary lives in the query cache under `SESSION_QUERY_KEY`; a
 * sign-out or any 401 while signed in clears the whole cache (nothing of the previous account
 * stays readable) and resets the summary to anonymous.
 */
export function createSessionStore(queryClient: QueryClient, port: SessionPort): SessionStore {
  const current = (): SessionSummary =>
    queryClient.getQueryData<SessionSummary>(SESSION_QUERY_KEY) ?? ANONYMOUS;

  const end = (
    event: Extract<SessionEvent, { type: 'signedOut' | 'unauthorized' | 'idle' }>,
  ): void => {
    // The summary is updated in place (its observers re-render) and every other cached query of
    // the previous account is dropped; removing the session query itself would detach its
    // observers from the replacement entry.
    queryClient.setQueryData<SessionSummary>(SESSION_QUERY_KEY, reduceSession(current(), event));
    queryClient.removeQueries({
      predicate: (query) => query.queryKey[0] !== SESSION_QUERY_KEY[0],
    });
  };

  const unsubscribe = port.subscribeUnauthorized(() => {
    if (current().state === 'signedIn') end({ type: 'unauthorized' });
  });

  return {
    queryKey: SESSION_QUERY_KEY,
    probeQuery: () => probeQueryOptions(port),
    current,
    async signIn(email, password) {
      const summary = await port.signIn(email, password);
      const next = reduceSession(current(), { type: 'signedIn', summary });
      queryClient.setQueryData<SessionSummary>(SESSION_QUERY_KEY, next);
      return next;
    },
    async signOut() {
      try {
        await port.signOut();
      } finally {
        end({ type: 'signedOut' });
      }
    },
    dispose: unsubscribe,
  };
}
