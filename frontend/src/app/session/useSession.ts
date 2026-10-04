import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useState } from 'react';

import type { Email } from '@domain/email';
import type { Password } from '@domain/password';

import {
  ANONYMOUS,
  sessionEndsSoon,
  type SessionStore,
  type SessionSummary,
} from './sessionStore.ts';

export const SessionStoreContext = createContext<SessionStore | undefined>(undefined);

export function useSessionStore(): SessionStore {
  const store = useContext(SessionStoreContext);
  if (store === undefined) {
    throw new Error('useSession requires a SessionStoreContext provider');
  }
  return store;
}

const CLOCK_TICK_MS = 15_000;

function useClock(enabled: boolean): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    if (!enabled) return undefined;
    setNow(new Date());
    const timer = setInterval(() => {
      setNow(new Date());
    }, CLOCK_TICK_MS);
    return () => {
      clearInterval(timer);
    };
  }, [enabled]);
  return now;
}

export type SessionHandle = {
  readonly summary: SessionSummary;
  /** True while the page-load probe has not answered yet. */
  readonly resolving: boolean;
  /** "Session about to end" warning: from `expiresAt − 2 min` onwards. */
  readonly endsSoon: boolean;
  readonly signIn: (email: Email, password: Password) => Promise<SessionSummary>;
  readonly signOut: () => Promise<void>;
};

export function useSession(): SessionHandle {
  const store = useSessionStore();
  const query = useQuery(store.probeQuery());
  const summary = query.data ?? ANONYMOUS;
  const now = useClock(summary.state === 'signedIn' && summary.expiresAt !== undefined);
  return {
    summary,
    resolving: query.isPending,
    endsSoon: sessionEndsSoon(summary, now),
    signIn: (email, password) => store.signIn(email, password),
    signOut: () => store.signOut(),
  };
}
