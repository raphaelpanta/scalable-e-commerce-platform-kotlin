import { type QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderResult } from '@testing-library/react';
import type { JSX, ReactNode } from 'react';
import { RouterProvider } from 'react-router';

import { createCartApi } from '@api/cart';
import { createCatalogApi } from '@api/catalog';
import { createIdentityApi } from '@api/identity';
import { createOrderApi } from '@api/order';
import { createPaymentApi } from '@api/payment';
import { createSessionPort } from '@api/session';
import { type CatalogPort, CatalogPortContext } from '@app/catalog/catalogPort';
import { type Ports, PortsContext } from '@app/ports';
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

import { API } from '../msw/catalog.ts';

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
  /** The real catalogue adapter over MSW (tests/msw/catalog.ts). */
  readonly catalog: CatalogPort;
  /** The real cart, identity, order and payment adapters over MSW (tests/msw/*). */
  readonly ports: Ports;
};

export type HarnessOptions = {
  /**
   * Sign in through the real session port over MSW (tests/msw/identity.ts) instead of the fake,
   * so the sign-in states (401, 403, 429) and the probe come from the fake identity.
   */
  readonly realSession?: boolean;
};

export function harness(
  initial: ProbeResult = { kind: 'anonymous' },
  { realSession = false }: HarnessOptions = {},
): Harness {
  const queryClient = createQueryClient();
  const port = fakeSessionPort(initial);
  const ports: Ports = {
    cart: createCartApi({ baseUrl: API }),
    identity: createIdentityApi({ baseUrl: API }),
    order: createOrderApi({ baseUrl: API }),
    payment: createPaymentApi({ baseUrl: API }),
  };
  const sessionStore = createSessionStore(
    queryClient,
    realSession ? createSessionPort({ baseUrl: API }, ports.identity) : port,
  );
  const catalog = createCatalogApi({ baseUrl: API });
  return { queryClient, port, sessionStore, catalog, ports };
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
      <SessionStoreContext.Provider value={h.sessionStore}>
        <CatalogPortContext.Provider value={h.catalog}>
          <PortsContext.Provider value={h.ports}>{children}</PortsContext.Provider>
        </CatalogPortContext.Provider>
      </SessionStoreContext.Provider>
    </QueryClientProvider>
  );
}

/** Renders the real route tree at `path` with the fake session. */
export function renderApp(
  path: string,
  initial: ProbeResult = { kind: 'anonymous' },
  options: HarnessOptions = {},
): RenderResult & { harness: Harness; router: ReturnType<typeof createTestRouter> } {
  const h = harness(initial, options);
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
