import { setupServer } from 'msw/node';

// One MSW server for the whole suite; tests add handlers with `server.use(...)`. Every request
// that no handler answers fails the test (`onUnhandledRequest: 'error'`, see tests/setup.ts), so
// a component can never silently reach a real network.
export const server = setupServer();
