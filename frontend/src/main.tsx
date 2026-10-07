import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';

import { createCartApi } from '@api/cart';
import { createCatalogApi } from '@api/catalog';
import { createIdentityApi } from '@api/identity';
import { createOrderApi } from '@api/order';
import { createPaymentApi } from '@api/payment';
import { createSessionPort } from '@api/session';
import { CatalogPortContext } from '@app/catalog/catalogPort';
import { type Ports, PortsContext } from '@app/ports';
import { createQueryClient } from '@app/queryClient';
import { createSessionStore } from '@app/session/sessionStore';
import { SessionStoreContext } from '@app/session/useSession';
import { createStorefrontRouter } from '@ui/routes/router';

import './ui/styles/tokens.css';
import './ui/styles/global.css';

// Composition root: the API edge is injected into the session store, the catalogue port and the
// shopping ports (cart, identity, order, payment), the store and the query client into the router.
// Browser telemetry joins here in a later phase (T093).
const queryClient = createQueryClient();
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
