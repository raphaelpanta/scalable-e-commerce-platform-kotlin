import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import * as fc from 'fast-check';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { cartServer } from './msw/cart.ts';
import { consoleServer } from './msw/console.ts';
import { identityServer } from './msw/identity.ts';
import { orderServer } from './msw/order.ts';
import { paymentServer } from './msw/payment.ts';
import { server } from './msw/server.ts';

// Reproducible property tests: FC_SEED pins the seed (the failure output prints it), otherwise
// fast-check picks one per run and reports it on failure.
const seedFromEnvironment = process.env['FC_SEED'];
fc.configureGlobal({
  numRuns: Number(process.env['FC_NUM_RUNS'] ?? 100),
  ...(seedFromEnvironment === undefined ? {} : { seed: Number(seedFromEnvironment) }),
  verbose: fc.VerbosityLevel.VeryVerbose,
});

// findBy* and waitFor retry until this deadline (1 s by default). On a loaded machine (the CI runner's verify, where
// Vitest shares the CPUs with the Gradle build) a fetch-and-render cycle took longer, and three UI tests failed on a
// state they would have reached; a passing test returns as soon as its assertion holds, so this costs nothing there.
configure({ asyncUtilTimeout: 5_000 });

beforeAll(() => {
  // MSW 3 names the option `onUnhandledFrame` (`onUnhandledRequest` in MSW 2): same semantics.
  server.listen({ onUnhandledFrame: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  cartServer.reset();
  consoleServer.reset();
  identityServer.reset();
  orderServer.reset();
  paymentServer.reset();
  window.sessionStorage.clear();
});

afterAll(() => {
  server.close();
});
