import { type QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import type { JSX, ReactNode } from 'react';
import { RouterProvider } from 'react-router';

import { createQueryClient } from '@app/queryClient';
import {
  createSessionStore,
  type ProbeResult,
  type SessionPort,
  type SessionStore,
  type SignInSummary,
} from '@app/session/sessionStore';
import { SessionStoreContext } from '@app/session/useSession';
import { createTestRouter } from '@ui/routes/router';

/** An in-memory session port: no network, every call recorded. */
export type FakeSessionPort = SessionPort & {
  readonly calls: string[];
  probeResult: ProbeResult;
  signInSummary: SignInSummary;
  emitUnauthorized(): void;
};

export function fakeSessionPort(initial: ProbeResult = { kind: 'anonymous' }): FakeSessionPort {
  const listeners = new Set<() => void>();
  const port: FakeSessionPort = {
    calls: [],
    probeResult: initial,
    signInSummary: { expiresAt: '2026-10-04T10:30:00Z', roles: ['shopper'] },
    probe: () => {
      port.calls.push('probe');
      return Promise.resolve(port.probeResult);
    },
    signIn: () => {
      port.calls.push('signIn');
      return Promise.resolve(port.signInSummary);
    },
    signOut: () => {
      port.calls.push('signOut');
      return Promise.resolve();
    },
    subscribeUnauthorized: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    emitUnauthorized: () => {
      for (const listener of listeners) listener();
    },
  };
  return port;
}

export type Harness = {
  readonly queryClient: QueryClient;
  readonly port: FakeSessionPort;
  readonly sessionStore: SessionStore;
};

export function harness(initial: ProbeResult = { kind: 'anonymous' }): Harness {
  const queryClient = createQueryClient();
  const port = fakeSessionPort(initial);
  const sessionStore = createSessionStore(queryClient, port);
  return { queryClient, port, sessionStore };
}

export function Providers({
  harness: h,
  children,
}: {
  harness: Harness;
  children: ReactNode;
}): JSX.Element {
  return (
    <QueryClientProvider client={h.queryClient}>
      <SessionStoreContext.Provider value={h.sessionStore}>{children}</SessionStoreContext.Provider>
    </QueryClientProvider>
  );
}

/** Renders the real route tree at `path` with the fake session. */
export function renderApp(
  path: string,
  initial: ProbeResult = { kind: 'anonymous' },
): RenderResult & { harness: Harness; router: ReturnType<typeof createTestRouter> } {
  const h = harness(initial);
  const router = createTestRouter(h, [path]);
  const result = render(
    <Providers harness={h}>
      <RouterProvider router={router} />
    </Providers>,
  );
  return { ...result, harness: h, router };
}

/** Renders a component that needs the providers but no router. */
export function renderWithProviders(
  ui: ReactNode,
  initial?: ProbeResult,
): RenderResult & { harness: Harness } {
  const h = harness(initial);
  return { ...render(<Providers harness={h}>{ui}</Providers>), harness: h };
}
