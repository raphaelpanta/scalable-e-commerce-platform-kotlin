import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';

import { createCartApi } from '@api/cart';
import { createCatalogApi } from '@api/catalog';
import { hasStatus } from '@api/client';
import { createIdentityApi } from '@api/identity';
import { createOrderApi } from '@api/order';
import { createPaymentApi } from '@api/payment';
import { createSessionPort } from '@api/session';
import { CatalogPortContext } from '@app/catalog/catalogPort';
import { correlation } from '@app/correlation';
import { type Ports, PortsContext } from '@app/ports';
import { createQueryClient } from '@app/queryClient';
import { createSessionStore } from '@app/session/sessionStore';
import { SessionStoreContext } from '@app/session/useSession';
import { initTelemetry, type Telemetry } from '@telemetry/index';
import { createStorefrontRouter } from '@ui/routes/router';

import './ui/styles/tokens.css';
import './ui/styles/global.css';

// Composition root: the API edge is injected into the session store, the catalogue port and the
// shopping ports (cart, identity, order, payment), the store and the query client into the router.
// Browser telemetry (FR-031, FR-032) starts first so the page load is observed; `VITE_TELEMETRY=off`
// (dev server without a gateway) turns it off. Failed queries and mutations are reported by class
// name and status only; an expected 401 (no session yet) is not a failure.
function startTelemetry(): Telemetry | undefined {
  if (import.meta.env['VITE_TELEMETRY'] === 'off') return undefined;
  try {
    return initTelemetry({ version: __APP_VERSION__, correlation, storage: window.sessionStorage });
  } catch {
    return undefined;
  }
}

const telemetry = startTelemetry();
const queryClient = createQueryClient();
function reportFailures(): void {
  const report = (error: unknown): void => {
    if (!hasStatus(error, 401)) telemetry?.reportFailure(error);
  };
  queryClient.getQueryCache().subscribe((event) => {
    if (event.type === 'updated' && event.action.type === 'error') report(event.action.error);
  });
  queryClient.getMutationCache().subscribe((event) => {
    if (event.type === 'updated' && event.action.type === 'error') report(event.action.error);
  });
}
reportFailures();
const identity = createIdentityApi();
const sessionStore = createSessionStore(queryClient, createSessionPort({}, identity));
const catalog = createCatalogApi();
const ports: Ports = {
  cart: createCartApi(),
  identity,
  order: createOrderApi(),
  payment: createPaymentApi(),
};
const router = createStorefrontRouter({ queryClient, sessionStore });

const container = document.getElementById('root');
if (container === null) throw new Error('index.html must contain <div id="root">');

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionStoreContext.Provider value={sessionStore}>
        <CatalogPortContext.Provider value={catalog}>
          <PortsContext.Provider value={ports}>
            <RouterProvider router={router} />
          </PortsContext.Provider>
        </CatalogPortContext.Provider>
      </SessionStoreContext.Provider>
    </QueryClientProvider>
  </StrictMode>,
);
