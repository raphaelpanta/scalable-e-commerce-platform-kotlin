import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import * as fc from 'fast-check';
import { afterAll, afterEach, beforeAll } from 'vitest';

import { server } from './msw/server.ts';

// Reproducible property tests: FC_SEED pins the seed (the failure output prints it), otherwise
// fast-check picks one per run and reports it on failure.
const seedFromEnvironment = process.env['FC_SEED'];
fc.configureGlobal({
  numRuns: Number(process.env['FC_NUM_RUNS'] ?? 100),
  ...(seedFromEnvironment === undefined ? {} : { seed: Number(seedFromEnvironment) }),
  verbose: fc.VerbosityLevel.VeryVerbose,
});

beforeAll(() => {
  // MSW 3 names the option `onUnhandledFrame` (`onUnhandledRequest` in MSW 2): same semantics.
  server.listen({ onUnhandledFrame: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  window.sessionStorage.clear();
});

afterAll(() => {
  server.close();
});
