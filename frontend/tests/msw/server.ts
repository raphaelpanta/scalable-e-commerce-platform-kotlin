import { setupServer } from 'msw/node';

import { cartHandlers } from './cart.ts';
import { catalogHandlers } from './catalog.ts';
import { consoleHandlers } from './console.ts';
import { identityHandlers } from './identity.ts';
import { orderHandlers } from './order.ts';
import { paymentHandlers } from './payment.ts';

// One MSW server for the whole suite, answering the catalogue from the fixtures of catalog.ts;
// tests add or override handlers with `server.use(...)` (reset after each test). Every request
// that no handler answers fails the test (`onUnhandledFrame: 'error'`, see tests/setup.ts), so
// a component can never silently reach a real network.
export const server = setupServer(
  // The console's operator handlers come first and fall through unless a console test armed them.
  ...consoleHandlers,
  ...catalogHandlers,
  ...cartHandlers,
  ...identityHandlers,
  ...orderHandlers,
  ...paymentHandlers,
);
