import { setupServer } from 'msw/node';

import { catalogHandlers } from './catalog.ts';

// One MSW server for the whole suite, answering the catalogue from the fixtures of catalog.ts;
// tests add or override handlers with `server.use(...)` (reset after each test). Every request
// that no handler answers fails the test (`onUnhandledFrame: 'error'`, see tests/setup.ts), so
// a component can never silently reach a real network.
export const server = setupServer(...catalogHandlers);
