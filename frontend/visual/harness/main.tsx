import { setupWorker } from 'msw/browser';

import { scenarioOf, seedScenario } from './scenarios.ts';
import { cartHandlers } from '../../tests/msw/cart.ts';
import { catalogHandlers } from '../../tests/msw/catalog.ts';
import { consoleHandlers } from '../../tests/msw/console.ts';
import { identityHandlers } from '../../tests/msw/identity.ts';
import { orderHandlers } from '../../tests/msw/order.ts';
import { paymentHandlers } from '../../tests/msw/payment.ts';

// Entry of the visual harness (visual/vite.visual.config.ts): the MSW browser worker answers the
// API with the component-test fakes, in the order of tests/msw/server.ts, seeded by the
// `?scenario=` of the first load; then the real composition root (src/main.tsx) mounts the app.
// Requests no handler answers (scripts, styles, fonts, fixture images) go to the harness origin.
const scenario = scenarioOf(window.location.search);
const overrides = seedScenario(scenario);

const worker = setupWorker(
  ...overrides,
  ...consoleHandlers,
  ...catalogHandlers,
  ...cartHandlers,
  ...identityHandlers,
  ...orderHandlers,
  ...paymentHandlers,
);
await worker.start({ quiet: true, onUnhandledFrame: 'bypass' });

// The scenario is harness state, not app state: drop it from the address before the router reads it.
const url = new URL(window.location.href);
url.searchParams.delete('scenario');
window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);

await import('../../src/main.tsx');
